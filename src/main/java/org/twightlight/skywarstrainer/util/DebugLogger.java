package org.twightlight.skywarstrainer.util;

import org.twightlight.skywarstrainer.SkyWarsTrainer;
import org.twightlight.skywarstrainer.bot.TrainerBot;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.Collections;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Centralized debug logging utility for the SkyWarsTrainer plugin.
 *
 * <p>All new systems MUST use this instead of direct plugin.getLogger() for
 * debug-level messages. Production messages (errors, warnings) still use
 * plugin.getLogger() directly.</p>
 *
 * <p>Supports per-bot debug toggles so developers can watch a single bot's
 * decision-making without being flooded by all bots.</p>
 */
public final class DebugLogger {

    /** Global debug enabled flag. */
    private static boolean globalDebugEnabled = false;

    /** Per-bot debug overrides. If a bot's UUID is in this set, its debug is forced on. */
    private static final Set<UUID> perBotDebug = Collections.newSetFromMap(new ConcurrentHashMap<>());

    /** Reference to the plugin for logging output. */
    private static SkyWarsTrainer pluginInstance;

    private DebugLogger() {
        // Static utility class
    }

    /**
     * Initializes the debug logger with the plugin instance and global setting.
     *
     * @param plugin the plugin instance
     */
    public static void init(@Nonnull SkyWarsTrainer plugin) {
        pluginInstance = plugin;
        globalDebugEnabled = plugin.getConfigManager().isDebugMode();
    }

    /**
     * Sets the global debug flag.
     *
     * @param enabled true to enable global debug
     */
    public static void setGlobalDebug(boolean enabled) {
        globalDebugEnabled = enabled;
    }

    /**
     * Returns whether global debug is enabled.
     *
     * @return true if global debug is on
     */
    public static boolean isGlobalDebugEnabled() {
        return globalDebugEnabled;
    }

    /**
     * Enables debug logging for a specific bot by UUID.
     *
     * @param botId the bot's UUID
     */
    public static void enableBotDebug(@Nonnull UUID botId) {
        perBotDebug.add(botId);
    }

    /**
     * Disables debug logging for a specific bot by UUID.
     *
     * @param botId the bot's UUID
     */
    public static void disableBotDebug(@Nonnull UUID botId) {
        perBotDebug.remove(botId);
    }

    /**
     * Checks whether debug is enabled for a given bot.
     * True if global debug is on OR if this bot has per-bot debug enabled.
     *
     * @param bot the bot to check
     * @return true if debug is enabled for this bot
     */
    public static boolean isDebugEnabled(@Nullable TrainerBot bot) {
        if (globalDebugEnabled) return true;
        if (bot != null && perBotDebug.contains(bot.getBotId())) return true;
        return false;
    }

    /**
     * Logs a debug message for a specific bot. Only outputs if debug is enabled
     * for the bot (either globally or per-bot).
     *
     * @param bot    the bot context (may be null for system-level messages)
     * @param format the format string (as in String.format)
     * @param args   format arguments
     */
    public static void log(@Nullable TrainerBot bot, @Nonnull String format, Object... args) {
        if (!isDebugEnabled(bot)) return;
        if (pluginInstance == null) return;

        String botName = (bot != null) ? bot.getName() : "SYSTEM";
        String message = String.format(format, args);
        pluginInstance.getLogger().info("[DEBUG] [" + botName + "] " + message);
    }

    /**
     * Logs a system-level debug message (no bot context).
     *
     * @param format the format string
     * @param args   format arguments
     */
    public static void logSystem(@Nonnull String format, Object... args) {
        log(null, format, args);
    }

    /**
     * Logs the execution time of a named subsystem tick. Useful for performance profiling.
     *
     * @param bot        the bot context
     * @param systemName the subsystem name
     * @param startNanos the System.nanoTime() at the start of the tick
     */
    public static void logTiming(@Nullable TrainerBot bot, @Nonnull String systemName, long startNanos) {
        if (!isDebugEnabled(bot)) return;
        if (pluginInstance == null) return;

        double ms = (System.nanoTime() - startNanos) / 1_000_000.0;
        if (ms > 1.0) { // Only log if > 1ms (to avoid noise)
            String botName = (bot != null) ? bot.getName() : "SYSTEM";
            pluginInstance.getLogger().info(
                    String.format("[PERF] [%s] %s took %.2fms", botName, systemName, ms));
        }
    }

    /**
     * Returns an unmodifiable view of all per-bot debug UUIDs.
     *
     * @return the set of bot UUIDs with debug enabled
     */
    @Nonnull
    public static Set<UUID> getPerBotDebugSet() {
        return Collections.unmodifiableSet(perBotDebug);
    }

    /**
     * Clears all per-bot debug toggles.
     */
    public static void clearAllPerBotDebug() {
        perBotDebug.clear();
    }
}
