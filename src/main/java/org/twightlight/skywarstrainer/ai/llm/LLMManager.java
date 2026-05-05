package org.twightlight.skywarstrainer.ai.llm;

import org.bukkit.Bukkit;
import org.twightlight.skywarstrainer.SkyWarsTrainer;
import org.twightlight.skywarstrainer.ai.decision.DecisionContext;
import org.twightlight.skywarstrainer.ai.decision.DecisionEngine.BotAction;
import org.twightlight.skywarstrainer.ai.strategy.StrategyPlan;
import org.twightlight.skywarstrainer.ai.strategy.StrategyPlanner;
import org.twightlight.skywarstrainer.bot.TrainerBot;
import org.twightlight.skywarstrainer.util.DebugLogger;

import javax.annotation.Nonnull;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * Server-wide singleton manager for the LLM integration system.
 *
 * <p>Handles rate limiting, async request dispatching, and response delivery.
 * All LLM API calls happen asynchronously; responses are queued and consumed
 * on the main thread during a scheduled tick task.</p>
 *
 * <p>If LLM is not enabled or no healthy API keys exist, all methods are no-ops.</p>
 */
public class LLMManager {

    private final SkyWarsTrainer plugin;
    private final LLMConfig config;
    private final LLMKeyManager keyManager;
    private final LLMClient client;

    /** Tracks the last request time per bot to enforce rate limiting. */
    private final Map<UUID, Long> lastRequestTimes;

    /** Queue of pending responses to be delivered on the main thread. */
    private final ConcurrentLinkedQueue<PendingResponse> responseQueue;

    /** Bukkit task ID for the response processing loop. */
    private int taskId;

    /**
     * Creates a new LLMManager.
     *
     * @param plugin the plugin instance
     */
    public LLMManager(@Nonnull SkyWarsTrainer plugin) {
        this.plugin = plugin;
        this.config = new LLMConfig(plugin);
        this.lastRequestTimes = new ConcurrentHashMap<>();
        this.responseQueue = new ConcurrentLinkedQueue<>();
        this.taskId = -1;

        if (config.isEnabled()) {
            this.keyManager = new LLMKeyManager(
                    config.getApiKeys(),
                    config.getRateLimitCooldownSeconds(),
                    config.getServerErrorCooldownSeconds());
            this.client = new LLMClient(config, keyManager);

            // Schedule response processing every 20 ticks (1 second)
            this.taskId = Bukkit.getScheduler().runTaskTimer(plugin, new Runnable() {
                @Override
                public void run() {
                    processPendingResponses();
                }
            }, 20L, 20L).getTaskId();

            DebugLogger.logSystem("LLM Manager initialized: provider=%s, model=%s, keys=%d",
                    config.getProvider(), config.getModel(), keyManager.getTotalKeyCount());
        } else {
            this.keyManager = null;
            this.client = null;
            DebugLogger.logSystem("LLM Manager: disabled (not configured or no API keys).");
        }
    }

    /**
     * Returns whether the LLM system is available for use.
     *
     * @return true if enabled and has healthy keys
     */
    public boolean isAvailable() {
        return config.isEnabled() && keyManager != null && keyManager.hasHealthyKeys();
    }

    /**
     * Requests strategic advice from the LLM for the given bot.
     * This method is rate-limited: it will silently return if called too frequently.
     *
     * @param bot the bot requesting advice
     */
    public void requestStrategicAdvice(@Nonnull TrainerBot bot) {
        if (!isAvailable()) return;
        if (client == null) return;

        UUID botId = bot.getBotId();
        long now = System.currentTimeMillis();
        long intervalMs = config.getRequestIntervalSeconds() * 1000L;

        // Rate limit check
        Long lastTime = lastRequestTimes.get(botId);
        if (lastTime != null && (now - lastTime) < intervalMs) {
            return;
        }
        lastRequestTimes.put(botId, now);

        // Build the prompt
        DecisionContext context = null;
        if (bot.getDecisionEngine() != null) {
            context = bot.getDecisionEngine().getContext();
        }
        if (context == null) return;

        StrategyPlan currentPlan = null;
        StrategyPlanner planner = bot.getStrategyPlanner();
        if (planner != null) {
            currentPlan = planner.getActivePlan();
        }

        String prompt = LLMPromptBuilder.buildStrategyPrompt(context, currentPlan, bot.getProfile());

        DebugLogger.log(bot, "LLM: Requesting strategic advice...");

        final UUID finalBotId = botId;
        client.requestAdvice(prompt).thenAccept(new java.util.function.Consumer<String>() {
            @Override
            public void accept(String response) {
                if (response != null) {
                    responseQueue.add(new PendingResponse(finalBotId, response));
                } else {
                    DebugLogger.logSystem("LLM: Received null response for bot %s",
                            finalBotId.toString().substring(0, 8));
                }
            }
        });
    }

    /**
     * Processes pending LLM responses on the main thread. Delivers parsed
     * advice to the appropriate bot's StrategyPlanner.
     */
    public void processPendingResponses() {
        PendingResponse pending;
        int processed = 0;
        while ((pending = responseQueue.poll()) != null && processed < 5) {
            processed++;

            TrainerBot bot = findBotById(pending.botId);
            if (bot == null || !bot.isAlive()) continue;

            LLMResponseParser.ParsedAdvice advice = LLMResponseParser.parse(pending.response);
            if (advice == null) {
                DebugLogger.log(bot, "LLM: Failed to parse response.");
                continue;
            }

            StrategyPlanner planner = bot.getStrategyPlanner();
            if (planner != null) {
                planner.onLLMAdviceReceived(advice.strategyDescription, advice.actionMultipliers);
            }

            DebugLogger.log(bot, "LLM advice delivered: %s (multipliers=%d)",
                    advice.strategyDescription, advice.actionMultipliers.size());
        }
    }

    /**
     * Finds a bot by its UUID. Searches the BotManager.
     */
    @javax.annotation.Nullable
    private TrainerBot findBotById(@Nonnull UUID botId) {
        if (plugin.getBotManager() == null) return null;
        return plugin.getBotManager().getBotById(botId);
    }

    /**
     * Returns the LLM config.
     *
     * @return the config
     */
    @Nonnull
    public LLMConfig getLLMConfig() {
        return config;
    }

    /**
     * Shuts down the LLM manager, cancels tasks, and cleans up resources.
     */
    public void shutdown() {
        if (taskId >= 0) {
            Bukkit.getScheduler().cancelTask(taskId);
            taskId = -1;
        }
        if (client != null) {
            client.shutdown();
        }
        responseQueue.clear();
        lastRequestTimes.clear();
        DebugLogger.logSystem("LLM Manager shut down.");
    }

    /**
     * Internal data class for queued LLM responses.
     */
    private static final class PendingResponse {
        final UUID botId;
        final String response;

        PendingResponse(@Nonnull UUID botId, @Nonnull String response) {
            this.botId = botId;
            this.response = response;
        }
    }
}
