package org.twightlight.skywarstrainer.ai.llm;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.twightlight.skywarstrainer.util.DebugLogger;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Handles asynchronous HTTP requests to LLM provider APIs.
 *
 * <p>Supports OpenAI, Google Gemini, Anthropic Claude, Cerebras, and OpenRouter.
 * All API calls happen on a dedicated thread pool to avoid blocking the main thread.</p>
 *
 * <p>Uses {@code java.net.HttpURLConnection} for Java 8 compatibility.</p>
 */
public class LLMClient {

    private final LLMConfig config;
    private final LLMKeyManager keyManager;
    private final ExecutorService executor;

    /**
     * Creates a new LLMClient.
     *
     * @param config     the LLM configuration
     * @param keyManager the key manager for API key rotation
     */
    public LLMClient(@Nonnull LLMConfig config, @Nonnull LLMKeyManager keyManager) {
        this.config = config;
        this.keyManager = keyManager;
        this.executor = Executors.newFixedThreadPool(2, new ThreadFactory() {
            private final AtomicInteger count = new AtomicInteger(0);
            @Override
            public Thread newThread(@Nonnull Runnable r) {
                Thread t = new Thread(r, "SkyWarsTrainer-LLM-" + count.incrementAndGet());
                t.setDaemon(true);
                return t;
            }
        });
    }

    /**
     * Sends an asynchronous request to the configured LLM provider.
     *
     * @param userPrompt the user message content
     * @return a CompletableFuture containing the LLM's response text, or null on failure
     */
    @Nonnull
    public CompletableFuture<String> requestAdvice(@Nonnull final String userPrompt) {
        final CompletableFuture<String> future = new CompletableFuture<>();

        executor.submit(new Runnable() {
            @Override
            public void run() {
                int retries = config.getMaxRetries();
                for (int attempt = 0; attempt <= retries; attempt++) {
                    String apiKey = keyManager.getNextHealthyKey();
                    if (apiKey == null) {
                        DebugLogger.logSystem("LLM: No healthy API keys available.");
                        future.complete(null);
                        return;
                    }

                    try {
                        String response = executeRequest(apiKey, userPrompt);
                        if (response != null) {
                            future.complete(response);
                            return;
                        }
                    } catch (Exception e) {
                        DebugLogger.logSystem("LLM request failed (attempt %d/%d): %s",
                                attempt + 1, retries + 1, e.getMessage());
                    }
                }

                future.complete(null);
            }
        });

        return future;
    }

    /**
     * Executes a synchronous HTTP request to the LLM API.
     *
     * @param apiKey     the API key to use
     * @param userPrompt the user message
     * @return the response text, or null on failure
     */
    @Nullable
    private String executeRequest(@Nonnull String apiKey, @Nonnull String userPrompt) throws Exception {
        String provider = config.getProvider();
        String endpoint = getEndpoint(provider);
        String requestBody = buildRequestBody(provider, apiKey, userPrompt);

        if (endpoint == null || requestBody == null) {
            DebugLogger.logSystem("LLM: Unknown provider '%s'", provider);
            return null;
        }

        // For Gemini, the key goes in the URL
        if ("GEMINI".equals(provider)) {
            endpoint = endpoint + "?key=" + apiKey;
        }

        URL url = new URL(endpoint);
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        try {
            conn.setRequestMethod("POST");
            conn.setDoOutput(true);
            conn.setConnectTimeout(config.getTimeoutSeconds() * 1000);
            conn.setReadTimeout(config.getTimeoutSeconds() * 1000);
            conn.setRequestProperty("Content-Type", "application/json");

            // Set auth headers per provider
            setAuthHeaders(conn, provider, apiKey);

            // Write request body
            byte[] bodyBytes = requestBody.getBytes(StandardCharsets.UTF_8);
            conn.setRequestProperty("Content-Length", String.valueOf(bodyBytes.length));
            OutputStream os = conn.getOutputStream();
            os.write(bodyBytes);
            os.flush();
            os.close();

            int responseCode = conn.getResponseCode();

            if (responseCode >= 200 && responseCode < 300) {
                BufferedReader reader = new BufferedReader(
                        new InputStreamReader(conn.getInputStream(), StandardCharsets.UTF_8));
                StringBuilder sb = new StringBuilder();
                String line;
                while ((line = reader.readLine()) != null) {
                    sb.append(line);
                }
                reader.close();
                return parseResponseBody(provider, sb.toString());
            } else {
                // Read error body for debugging
                BufferedReader errorReader = null;
                try {
                    if (conn.getErrorStream() != null) {
                        errorReader = new BufferedReader(
                                new InputStreamReader(conn.getErrorStream(), StandardCharsets.UTF_8));
                        StringBuilder errSb = new StringBuilder();
                        String errLine;
                        while ((errLine = errorReader.readLine()) != null) {
                            errSb.append(errLine);
                        }
                        DebugLogger.logSystem("LLM HTTP %d: %s", responseCode, errSb.toString());
                    }
                } finally {
                    if (errorReader != null) {
                        errorReader.close();
                    }
                }

                keyManager.markKeyFailed(apiKey, responseCode);
                return null;
            }
        } finally {
            conn.disconnect();
        }
    }

    /**
     * Returns the API endpoint URL for the given provider.
     */
    @Nullable
    private String getEndpoint(@Nonnull String provider) {
        switch (provider) {
            case "OPENAI": {
                String custom = config.getCustomEndpointOpenAI();
                if (custom != null && !custom.isEmpty()) return custom;
                return "https://api.openai.com/v1/chat/completions";
            }
            case "GEMINI": {
                String custom = config.getCustomEndpointGemini();
                if (custom != null && !custom.isEmpty()) return custom;
                return "https://generativelanguage.googleapis.com/v1beta/models/"
                        + config.getModel() + ":generateContent";
            }
            case "ANTHROPIC": {
                String custom = config.getCustomEndpointAnthropic();
                if (custom != null && !custom.isEmpty()) return custom;
                return "https://api.anthropic.com/v1/messages";
            }
            case "CEREBRAS": {
                String custom = config.getCustomEndpointCerebras();
                if (custom != null && !custom.isEmpty()) return custom;
                return "https://api.cerebras.ai/v1/chat/completions";
            }
            case "OPENROUTER": {
                String custom = config.getCustomEndpointOpenRouter();
                if (custom != null && !custom.isEmpty()) return custom;
                return "https://openrouter.ai/api/v1/chat/completions";
            }
            default:
                return null;
        }
    }

    /**
     * Sets authentication headers for the given provider.
     */
    private void setAuthHeaders(@Nonnull HttpURLConnection conn,
                                @Nonnull String provider,
                                @Nonnull String apiKey) {
        switch (provider) {
            case "OPENAI":
            case "CEREBRAS":
            case "OPENROUTER":
                conn.setRequestProperty("Authorization", "Bearer " + apiKey);
                break;
            case "ANTHROPIC":
                conn.setRequestProperty("x-api-key", apiKey);
                conn.setRequestProperty("anthropic-version", "2023-06-01");
                break;
            case "GEMINI":
                // Key is in URL query parameter
                break;
        }
    }

    /**
     * Builds the JSON request body for the given provider.
     */
    @Nullable
    private String buildRequestBody(@Nonnull String provider,
                                    @Nonnull String apiKey,
                                    @Nonnull String userPrompt) {
        String systemPrompt = config.getSystemPrompt();

        switch (provider) {
            case "OPENAI":
            case "CEREBRAS":
            case "OPENROUTER": {
                JsonObject body = new JsonObject();
                body.addProperty("model", config.getModel());
                body.addProperty("max_tokens", config.getMaxTokens());
                body.addProperty("temperature", config.getTemperature());
                JsonArray messages = new JsonArray();

                if (systemPrompt != null && !systemPrompt.isEmpty()) {
                    JsonObject sysMsg = new JsonObject();
                    sysMsg.addProperty("role", "system");
                    sysMsg.addProperty("content", systemPrompt);
                    messages.add(sysMsg);
                }

                JsonObject userMsg = new JsonObject();
                userMsg.addProperty("role", "user");
                userMsg.addProperty("content", userPrompt);
                messages.add(userMsg);

                body.add("messages", messages);
                return body.toString();
            }
            case "GEMINI": {
                JsonObject body = new JsonObject();
                JsonArray contents = new JsonArray();
                JsonObject content = new JsonObject();
                JsonArray parts = new JsonArray();
                JsonObject part = new JsonObject();

                String combinedPrompt = (systemPrompt != null && !systemPrompt.isEmpty())
                        ? systemPrompt + "\n\n" + userPrompt
                        : userPrompt;
                part.addProperty("text", combinedPrompt);
                parts.add(part);
                content.add("parts", parts);
                contents.add(content);
                body.add("contents", contents);

                JsonObject genConfig = new JsonObject();
                genConfig.addProperty("maxOutputTokens", config.getMaxTokens());
                genConfig.addProperty("temperature", config.getTemperature());
                body.add("generationConfig", genConfig);

                return body.toString();
            }
            case "ANTHROPIC": {
                JsonObject body = new JsonObject();
                body.addProperty("model", config.getModel());
                body.addProperty("max_tokens", config.getMaxTokens());

                if (systemPrompt != null && !systemPrompt.isEmpty()) {
                    body.addProperty("system", systemPrompt);
                }

                JsonArray messages = new JsonArray();
                JsonObject userMsg = new JsonObject();
                userMsg.addProperty("role", "user");
                userMsg.addProperty("content", userPrompt);
                messages.add(userMsg);
                body.add("messages", messages);

                return body.toString();
            }
            default:
                return null;
        }
    }

    /**
     * Parses the response body and extracts the generated text content.
     */
    @Nullable
    private String parseResponseBody(@Nonnull String provider, @Nonnull String responseBody) {
        try {
            JsonObject json = new JsonParser().parse(responseBody).getAsJsonObject();

            switch (provider) {
                case "OPENAI":
                case "CEREBRAS":
                case "OPENROUTER": {
                    JsonArray choices = json.getAsJsonArray("choices");
                    if (choices != null && choices.size() > 0) {
                        JsonObject message = choices.get(0).getAsJsonObject().getAsJsonObject("message");
                        if (message != null) {
                            return message.get("content").getAsString();
                        }
                    }
                    return null;
                }
                case "GEMINI": {
                    JsonArray candidates = json.getAsJsonArray("candidates");
                    if (candidates != null && candidates.size() > 0) {
                        JsonObject content = candidates.get(0).getAsJsonObject().getAsJsonObject("content");
                        if (content != null) {
                            JsonArray parts = content.getAsJsonArray("parts");
                            if (parts != null && parts.size() > 0) {
                                return parts.get(0).getAsJsonObject().get("text").getAsString();
                            }
                        }
                    }
                    return null;
                }
                case "ANTHROPIC": {
                    JsonArray contentArray = json.getAsJsonArray("content");
                    if (contentArray != null && contentArray.size() > 0) {
                        return contentArray.get(0).getAsJsonObject().get("text").getAsString();
                    }
                    return null;
                }
                default:
                    return null;
            }
        } catch (Exception e) {
            DebugLogger.logSystem("LLM response parse error: %s", e.getMessage());
            return null;
        }
    }

    /**
     * Shuts down the executor service.
     */
    public void shutdown() {
        executor.shutdownNow();
    }
}
