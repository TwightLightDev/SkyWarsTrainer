package org.twightlight.skywarstrainer.commands.subcommands;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.command.CommandSender;
import org.twightlight.skywarstrainer.SkyWarsTrainer;
import org.twightlight.skywarstrainer.ai.llm.LLMConfig;
import org.twightlight.skywarstrainer.ai.llm.LLMManager;
import org.twightlight.skywarstrainer.commands.CommandHandler;

import javax.annotation.Nonnull;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * /swt validatekeys [<provider>] — Tests all configured LLM API keys by hitting
 * each provider's real endpoint and reports results back with color-coded status.
 *
 * <p>Runs fully async to avoid blocking the main thread. Results are delivered
 * back on the main thread via {@code Bukkit.getScheduler().runTask()}.</p>
 *
 * <p>Keys are masked in output (e.g. {@code sk-p...xY9z}) so they are never
 * leaked in chat.</p>
 */
public class ValidateKeysCommand implements SubCommand {

    private static final String PREFIX = CommandHandler.getPrefix();

    private final SkyWarsTrainer plugin;

    public ValidateKeysCommand(@Nonnull SkyWarsTrainer plugin) {
        this.plugin = plugin;
    }

    @Override
    public void execute(@Nonnull final CommandSender sender, @Nonnull final String[] args) {
        final LLMManager llmManager = plugin.getLLMManager();
        if (llmManager == null) {
            sender.sendMessage(PREFIX + ChatColor.RED + "LLM system is not initialized.");
            return;
        }

        final LLMConfig config = llmManager.getLLMConfig();
        final List<String> apiKeys = config.getApiKeys();

        if (apiKeys.isEmpty()) {
            sender.sendMessage(PREFIX + ChatColor.RED + "No API keys configured in llm_config.yml.");
            return;
        }

        // Determine which provider to test, or all if none specified
        final String filterProvider;
        if (args.length >= 1) {
            filterProvider = args[0].toUpperCase();
            if (!isValidProvider(filterProvider)) {
                sender.sendMessage(PREFIX + ChatColor.RED + "Unknown provider: " + args[0]);
                sender.sendMessage(PREFIX + "Valid providers: OPENAI, GEMINI, ANTHROPIC, CEREBRAS, OPENROUTER");
                return;
            }
        } else {
            filterProvider = null; // test the configured provider
        }

        final String provider = (filterProvider != null) ? filterProvider : config.getProvider();
        final String model = config.getModel();
        final int timeoutSeconds = config.getTimeoutSeconds();

        sender.sendMessage(PREFIX + ChatColor.AQUA + "Validating " + apiKeys.size()
                + " key(s) against " + ChatColor.WHITE + provider
                + ChatColor.AQUA + "...");

        // Run all validations async
        Bukkit.getScheduler().runTaskAsynchronously(plugin, new Runnable() {
            @Override
            public void run() {
                final List<KeyResult> results = new ArrayList<KeyResult>();
                final AtomicInteger completed = new AtomicInteger(0);

                for (int i = 0; i < apiKeys.size(); i++) {
                    final String key = apiKeys.get(i);
                    final int index = i + 1;
                    KeyResult result = testKey(provider, model, key, timeoutSeconds, index);
                    results.add(result);
                }

                // Deliver results on the main thread
                Bukkit.getScheduler().runTask(plugin, new Runnable() {
                    @Override
                    public void run() {
                        sendReport(sender, provider, results);
                    }
                });
            }
        });
    }

    /**
     * Tests a single API key by sending a minimal request to the provider.
     */
    private KeyResult testKey(String provider, String model, String key, int timeoutSeconds, int index) {
        HttpURLConnection conn = null;
        try {
            String endpoint = getEndpoint(provider, model, key);
            if (endpoint == null) {
                return new KeyResult(index, key, -1, "Unknown provider: " + provider, -1);
            }

            URL url = new URL(endpoint);
            conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("POST");
            conn.setDoOutput(true);
            conn.setConnectTimeout(timeoutSeconds * 1000);
            conn.setReadTimeout(timeoutSeconds * 1000);
            conn.setRequestProperty("Content-Type", "application/json");

            // Set auth headers per provider
            setAuthHeaders(conn, provider, key);

            // Build minimal request body
            String body = buildMinimalBody(provider, model);

            long start = System.currentTimeMillis();

            byte[] bodyBytes = body.getBytes(StandardCharsets.UTF_8);
            conn.setRequestProperty("Content-Length", String.valueOf(bodyBytes.length));
            OutputStream os = conn.getOutputStream();
            os.write(bodyBytes);
            os.flush();
            os.close();

            int code = conn.getResponseCode();
            long elapsed = System.currentTimeMillis() - start;

            // Read response or error body (just for debug, we don't display it)
            String responseDetail = "";
            try {
                if (code >= 200 && code < 300) {
                    BufferedReader reader = new BufferedReader(
                            new InputStreamReader(conn.getInputStream(), StandardCharsets.UTF_8));
                    StringBuilder sb = new StringBuilder();
                    String line;
                    while ((line = reader.readLine()) != null) {
                        sb.append(line);
                    }
                    reader.close();
                    responseDetail = "OK";
                } else if (conn.getErrorStream() != null) {
                    BufferedReader reader = new BufferedReader(
                            new InputStreamReader(conn.getErrorStream(), StandardCharsets.UTF_8));
                    StringBuilder sb = new StringBuilder();
                    String line;
                    while ((line = reader.readLine()) != null) {
                        sb.append(line);
                    }
                    reader.close();
                    responseDetail = truncate(sb.toString(), 80);
                }
            } catch (Exception ignored) {
                // swallow read errors
            }

            return new KeyResult(index, key, code, responseDetail, elapsed);
        } catch (java.net.SocketTimeoutException e) {
            return new KeyResult(index, key, 0, "Timeout", -1);
        } catch (Exception e) {
            return new KeyResult(index, key, -1, e.getClass().getSimpleName() + ": " + e.getMessage(), -1);
        } finally {
            if (conn != null) {
                conn.disconnect();
            }
        }
    }

    /**
     * Sends the formatted validation report to the sender.
     */
    private void sendReport(CommandSender sender, String provider, List<KeyResult> results) {
        sender.sendMessage("");
        sender.sendMessage(ChatColor.GOLD + "\u2501\u2501\u2501 LLM Key Validation Report \u2501\u2501\u2501");
        sender.sendMessage(ChatColor.GRAY + "Provider: " + ChatColor.WHITE + provider
                + ChatColor.GRAY + "  |  Keys tested: " + ChatColor.WHITE + results.size());
        sender.sendMessage("");

        int valid = 0;
        int authFail = 0;
        int transient_ = 0;

        for (int i = 0; i < results.size(); i++) {
            KeyResult r = results.get(i);
            String masked = maskKey(r.key);
            String status;
            ChatColor color;

            if (r.httpCode >= 200 && r.httpCode < 300) {
                // Valid
                status = ChatColor.GREEN + "\u2714 Valid";
                color = ChatColor.GREEN;
                valid++;
            } else if (r.httpCode == 401 || r.httpCode == 403) {
                // Auth failure — permanent
                status = ChatColor.RED + "\u2718 Auth Failed (HTTP " + r.httpCode + ")";
                color = ChatColor.RED;
                authFail++;
            } else if (r.httpCode == 429 || r.httpCode >= 500 || r.httpCode == 0 || r.httpCode == -1) {
                // Transient — rate limit, server error, timeout, or connection error
                String reason;
                if (r.httpCode == 429) {
                    reason = "Rate Limited";
                } else if (r.httpCode >= 500) {
                    reason = "Server Error (HTTP " + r.httpCode + ")";
                } else if (r.httpCode == 0) {
                    reason = "Timeout";
                } else {
                    reason = r.detail;
                }
                status = ChatColor.YELLOW + "\u2718 " + reason;
                color = ChatColor.YELLOW;
                transient_++;
            } else {
                // Other HTTP errors (e.g. 400, 404)
                status = ChatColor.RED + "\u2718 HTTP " + r.httpCode;
                color = ChatColor.RED;
                authFail++;
            }

            String timing = (r.elapsedMs >= 0) ? ChatColor.GRAY + " (" + r.elapsedMs + "ms)" : "";
            sender.sendMessage(ChatColor.GRAY + " #" + r.index + " "
                    + ChatColor.WHITE + masked + " " + status + timing);
        }

        sender.sendMessage("");
        sender.sendMessage(ChatColor.GRAY + "Summary: "
                + ChatColor.GREEN + valid + " valid" + ChatColor.GRAY + " | "
                + ChatColor.RED + authFail + " failed" + ChatColor.GRAY + " | "
                + ChatColor.YELLOW + transient_ + " transient");
        sender.sendMessage(ChatColor.GOLD + "\u2501\u2501\u2501\u2501\u2501\u2501\u2501\u2501\u2501\u2501"
                + "\u2501\u2501\u2501\u2501\u2501\u2501\u2501\u2501\u2501\u2501\u2501\u2501\u2501\u2501\u2501"
                + "\u2501\u2501\u2501\u2501\u2501\u2501\u2501\u2501\u2501\u2501");
    }

    // ━━━ Provider-specific helpers ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

    private String getEndpoint(String provider, String model, String key) {
        switch (provider) {
            case "OPENAI":
                return "https://api.openai.com/v1/chat/completions";
            case "GEMINI":
                return "https://generativelanguage.googleapis.com/v1beta/models/"
                        + model + ":generateContent?key=" + key;
            case "ANTHROPIC":
                return "https://api.anthropic.com/v1/messages";
            case "CEREBRAS":
                return "https://api.cerebras.ai/v1/chat/completions";
            case "OPENROUTER":
                return "https://openrouter.ai/api/v1/chat/completions";
            default:
                return null;
        }
    }

    private void setAuthHeaders(HttpURLConnection conn, String provider, String key) {
        switch (provider) {
            case "OPENAI":
            case "CEREBRAS":
            case "OPENROUTER":
                conn.setRequestProperty("Authorization", "Bearer " + key);
                break;
            case "ANTHROPIC":
                conn.setRequestProperty("x-api-key", key);
                conn.setRequestProperty("anthropic-version", "2023-06-01");
                break;
            case "GEMINI":
                // Key is in URL query parameter
                break;
        }
    }

    private String buildMinimalBody(String provider, String model) {
        switch (provider) {
            case "OPENAI":
            case "CEREBRAS":
            case "OPENROUTER":
                return "{\"model\":\"" + escapeJson(model)
                        + "\",\"max_tokens\":1,\"messages\":"
                        + "[{\"role\":\"user\",\"content\":\"Hi\"}]}";
            case "GEMINI":
                return "{\"contents\":[{\"parts\":[{\"text\":\"Hi\"}]}],"
                        + "\"generationConfig\":{\"maxOutputTokens\":1}}";
            case "ANTHROPIC":
                return "{\"model\":\"" + escapeJson(model)
                        + "\",\"max_tokens\":1,\"messages\":"
                        + "[{\"role\":\"user\",\"content\":\"Hi\"}]}";
            default:
                return "{}";
        }
    }

    // ━━━ Utility methods ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

    /**
     * Masks a key to show only the first 4 and last 4 characters.
     * Example: "sk-proj-abc123xyz789" → "sk-p...z789"
     */
    private static String maskKey(String key) {
        if (key == null) return "null";
        if (key.length() <= 8) return "****";
        return key.substring(0, 4) + "..." + key.substring(key.length() - 4);
    }

    private static String escapeJson(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    private static String truncate(String s, int max) {
        if (s == null) return "";
        if (s.length() <= max) return s;
        return s.substring(0, max) + "...";
    }

    private boolean isValidProvider(String provider) {
        return "OPENAI".equals(provider) || "GEMINI".equals(provider)
                || "ANTHROPIC".equals(provider) || "CEREBRAS".equals(provider)
                || "OPENROUTER".equals(provider);
    }

    // ━━━ SubCommand contract ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

    @Override
    @Nonnull
    public String getPermission() {
        return "skywarstrainer.admin";
    }

    @Override
    @Nonnull
    public String getUsage() {
        return "/swt validatekeys [<provider>]";
    }

    @Override
    @Nonnull
    public String getDescription() {
        return "Test all configured LLM API keys and report validity.";
    }

    // ━━━ Internal result class ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

    private static final class KeyResult {
        final int index;
        final String key;
        final int httpCode;
        final String detail;
        final long elapsedMs;

        KeyResult(int index, String key, int httpCode, String detail, long elapsedMs) {
            this.index = index;
            this.key = key;
            this.httpCode = httpCode;
            this.detail = detail;
            this.elapsedMs = elapsedMs;
        }
    }
}
