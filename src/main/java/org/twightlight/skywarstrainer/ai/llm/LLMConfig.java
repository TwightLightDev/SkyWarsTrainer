package org.twightlight.skywarstrainer.ai.llm;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.twightlight.skywarstrainer.SkyWarsTrainer;

import javax.annotation.Nonnull;
import java.io.File;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Loads and provides access to the LLM integration configuration from {@code llm_config.yml}.
 *
 * <p>This configuration is entirely optional. If the file is missing or {@code enabled}
 * is false, the LLM system remains completely dormant with zero impact.</p>
 */
public class LLMConfig {

    private final SkyWarsTrainer plugin;

    // ── Core settings ──
    private boolean enabled;
    private String provider;
    private List<String> apiKeys;
    private String model;
    private int requestIntervalSeconds;
    private int maxTokens;
    private double temperature;
    private int timeoutSeconds;
    private int maxRetries;
    private String systemPrompt;

    // ── Key management ──
    private int rateLimitCooldownSeconds;
    private int serverErrorCooldownSeconds;

    // ── Custom endpoints ──
    private String customEndpointOpenAI;
    private String customEndpointGemini;
    private String customEndpointAnthropic;
    private String customEndpointCerebras;
    private String customEndpointOpenRouter;

    /**
     * Creates a new LLMConfig and loads settings from llm_config.yml.
     *
     * @param plugin the plugin instance
     */
    public LLMConfig(@Nonnull SkyWarsTrainer plugin) {
        this.plugin = plugin;
        load();
    }

    /**
     * Loads or reloads the LLM configuration from llm_config.yml.
     */
    public void load() {
        File file = new File(plugin.getDataFolder(), "llm_config.yml");
        if (!file.exists()) {
            // File doesn't exist — LLM is disabled
            this.enabled = false;
            this.provider = "OPENAI";
            this.apiKeys = Collections.emptyList();
            this.model = "gpt-4o-mini";
            this.requestIntervalSeconds = 30;
            this.maxTokens = 200;
            this.temperature = 0.3;
            this.timeoutSeconds = 10;
            this.maxRetries = 2;
            this.systemPrompt = "";
            this.rateLimitCooldownSeconds = 60;
            this.serverErrorCooldownSeconds = 30;
            this.customEndpointOpenAI = "";
            this.customEndpointGemini = "";
            this.customEndpointAnthropic = "";
            this.customEndpointCerebras = "";
            this.customEndpointOpenRouter = "";
            return;
        }

        FileConfiguration config = YamlConfiguration.loadConfiguration(file);

        // Load defaults from resource
        InputStream defaultStream = plugin.getResource("llm_config.yml");
        if (defaultStream != null) {
            YamlConfiguration defaultConfig = YamlConfiguration.loadConfiguration(
                    new InputStreamReader(defaultStream, StandardCharsets.UTF_8));
            config.setDefaults(defaultConfig);
        }

        // ── Core settings ──
        ConfigurationSection llmSection = config.getConfigurationSection("llm");
        if (llmSection != null) {
            this.enabled = llmSection.getBoolean("enabled", false);
            this.provider = llmSection.getString("provider", "OPENAI").toUpperCase();
            this.apiKeys = llmSection.getStringList("api-keys");
            if (this.apiKeys == null) {
                this.apiKeys = new ArrayList<>();
            }
            this.model = llmSection.getString("model", "gpt-4o-mini");
            this.requestIntervalSeconds = llmSection.getInt("request-interval-seconds", 30);
            this.maxTokens = llmSection.getInt("max-tokens", 200);
            this.temperature = llmSection.getDouble("temperature", 0.3);
            this.timeoutSeconds = llmSection.getInt("timeout-seconds", 10);
            this.maxRetries = llmSection.getInt("max-retries", 2);
            this.systemPrompt = llmSection.getString("system-prompt", "");
        } else {
            this.enabled = false;
            this.provider = "OPENAI";
            this.apiKeys = new ArrayList<>();
            this.model = "gpt-4o-mini";
            this.requestIntervalSeconds = 30;
            this.maxTokens = 200;
            this.temperature = 0.3;
            this.timeoutSeconds = 10;
            this.maxRetries = 2;
            this.systemPrompt = "";
        }

        // ── Key management ──
        ConfigurationSection keyMgmt = config.getConfigurationSection("key-management");
        if (keyMgmt != null) {
            this.rateLimitCooldownSeconds = keyMgmt.getInt("rate-limit-cooldown-seconds", 60);
            this.serverErrorCooldownSeconds = keyMgmt.getInt("server-error-cooldown-seconds", 30);
        } else {
            this.rateLimitCooldownSeconds = 60;
            this.serverErrorCooldownSeconds = 30;
        }

        // ── Custom endpoints ──
        ConfigurationSection endpoints = config.getConfigurationSection("custom-endpoints");
        if (endpoints != null) {
            this.customEndpointOpenAI = endpoints.getString("openai", "");
            this.customEndpointGemini = endpoints.getString("gemini", "");
            this.customEndpointAnthropic = endpoints.getString("anthropic", "");
            this.customEndpointCerebras = endpoints.getString("cerebras", "");
            this.customEndpointOpenRouter = endpoints.getString("openrouter", "");
        } else {
            this.customEndpointOpenAI = "";
            this.customEndpointGemini = "";
            this.customEndpointAnthropic = "";
            this.customEndpointCerebras = "";
            this.customEndpointOpenRouter = "";
        }

        // Validate: if enabled but no keys, disable
        if (enabled && (apiKeys.isEmpty() || (apiKeys.size() == 1 && apiKeys.get(0).equals("YOUR_API_KEY_HERE")))) {
            enabled = false;
            plugin.getLogger().info("[LLM] LLM integration enabled in config but no valid API keys provided. Disabling.");
        }

        if (enabled) {
            plugin.getLogger().info("[LLM] LLM integration enabled: provider=" + provider
                    + ", model=" + model + ", keys=" + apiKeys.size());
        }
    }

    // ─── Getters ────────────────────────────────────────────────

    /** @return whether the LLM system is enabled */
    public boolean isEnabled() { return enabled; }

    /** @return the provider name (OPENAI, GEMINI, ANTHROPIC, CEREBRAS, OPENROUTER) */
    @Nonnull
    public String getProvider() { return provider; }

    /** @return unmodifiable list of API keys */
    @Nonnull
    public List<String> getApiKeys() { return Collections.unmodifiableList(apiKeys); }

    /** @return the model identifier */
    @Nonnull
    public String getModel() { return model; }

    /** @return minimum seconds between LLM requests per bot */
    public int getRequestIntervalSeconds() { return requestIntervalSeconds; }

    /** @return max tokens in LLM response */
    public int getMaxTokens() { return maxTokens; }

    /** @return temperature for response generation */
    public double getTemperature() { return temperature; }

    /** @return HTTP timeout in seconds */
    public int getTimeoutSeconds() { return timeoutSeconds; }

    /** @return max retry attempts */
    public int getMaxRetries() { return maxRetries; }

    /** @return the system prompt for the LLM */
    @Nonnull
    public String getSystemPrompt() { return systemPrompt; }

    /** @return cooldown seconds for rate-limited keys (HTTP 429) */
    public int getRateLimitCooldownSeconds() { return rateLimitCooldownSeconds; }

    /** @return cooldown seconds for server error keys (HTTP 500/timeout) */
    public int getServerErrorCooldownSeconds() { return serverErrorCooldownSeconds; }

    /** @return custom OpenAI endpoint, or empty string for default */
    @Nonnull
    public String getCustomEndpointOpenAI() { return customEndpointOpenAI; }

    /** @return custom Gemini endpoint, or empty string for default */
    @Nonnull
    public String getCustomEndpointGemini() { return customEndpointGemini; }

    /** @return custom Anthropic endpoint, or empty string for default */
    @Nonnull
    public String getCustomEndpointAnthropic() { return customEndpointAnthropic; }

    /** @return custom Cerebras endpoint, or empty string for default */
    @Nonnull
    public String getCustomEndpointCerebras() { return customEndpointCerebras; }

    /** @return custom OpenRouter endpoint, or empty string for default */
    @Nonnull
    public String getCustomEndpointOpenRouter() { return customEndpointOpenRouter; }
}
