package org.twightlight.skywarstrainer.util;

import org.twightlight.skywarstrainer.SkyWarsTrainerPlugin;
import org.twightlight.skywarstrainer.bot.TrainerBot;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.logging.Level;

/**
 * Centralized debug logging utility for SkyWarsTrainer.
 *
 * <p>All debug-level messages should go through this class rather than
 * directly using {@code plugin.getLogger().info("[DEBUG] ...")}. This
 * ensures debug output is only generated when debug mode is active,
 * avoiding unnecessary string concatenation and method calls in production.</p>
 *
 * <p>Debug mode can be toggled globally via config or per-bot via the
 * /swt debug command. Messages are suppressed entirely (not even
 * string-formatted) when debug is off, minimizing performance impact.</p>
 *
 * <h3>Usage:</h3>
 * <pre>
 * // Global debug message (only prints if global debug is on)
 * DebugLogger.log("Something happened: %s", someValue);
 *
 * // Bot-specific debug message (prints if global OR bot-specific debug is on)
 * DebugLogger.log(bot, "State changed to %s", newState);
 *
 * // Performance profiling (always guarded by debug check)
 * DebugLogger.logTiming("mapScan", elapsedNanos);
 * </pre>
 */
public final class DebugLogger {

    /** Global debug mode flag — cached from ConfigManager for fast access. */
    private static volatile boolean globalDebugEnabled = false;

    /** Reference to plugin for the logger. */
    private static SkyWarsTrainerPlugin pluginRef;

    private DebugLogger() {
        // Utility class — no instantiation
    }

    /**
     * Initializes the debug logger with the plugin reference and current
     * debug mode setting. Call this during plugin enable.
     *
     * @param plugin the plugin instance
     */
    public static void init(@Nonnull SkyWarsTrainerPlugin plugin) {
        pluginRef = plugin;
        globalDebugEnabled = plugin.getConfigManager().isDebugMode();
    }

    /**
     * Updates the global debug mode flag. Called when debug mode is toggled
     * at runtime via command or config reload.
     *
     * @param enabled whether global debug is now enabled
     */
    public static void setGlobalDebugEnabled(boolean enabled) {
        globalDebugEnabled = enabled;
        if (pluginRef != null) {
            pluginRef.getLogger().info("Debug mode " + (enabled ? "ENABLED" : "DISABLED"));
        }
    }

    /**
     * Returns whether global debug mode is currently active.
     *
     * @return true if global debug is on
     */
    public static boolean isGlobalDebugEnabled() {
        return globalDebugEnabled;
    }

    /**
     * Returns whether debug output should be generated for the given bot.
     * True if either global debug or bot-specific debug is enabled.
     *
     * @param bot the bot to check, or null for global-only check
     * @return true if debug output should be generated
     */
    public static boolean isDebugEnabled(@Nullable TrainerBot bot) {
        if (globalDebugEnabled) return true;
        return bot != null && bot.getProfile().isDebugMode();
    }

    /**
     * Logs a global debug message. Only generates the message string if
     * global debug mode is active.
     *
     * @param format the format string (printf-style)
     * @param args   the format arguments
     */
    public static void log(@Nonnull String format, Object... args) {
        if (!globalDebugEnabled) return;
        if (pluginRef == null) return;
        pluginRef.getLogger().info("[DEBUG] " + String.format(format, args));
    }

    /**
     * Logs a bot-specific debug message. Only generates the message string if
     * debug mode is active for the given bot (either globally or per-bot).
     *
     * @param bot    the bot this message relates to
     * @param format the format string (printf-style)
     * @param args   the format arguments
     */
    public static void log(@Nonnull TrainerBot bot, @Nonnull String format, Object... args) {
        if (!isDebugEnabled(bot)) return;
        if (pluginRef == null) return;
        pluginRef.getLogger().info("[DEBUG] [" + bot.getName() + "] " + String.format(format, args));
    }

    /**
     * Logs a performance timing message. Used by subsystem tick profiling
     * to identify bottlenecks.
     *
     * @param systemName   the subsystem name (e.g., "mapScan", "combat")
     * @param elapsedNanos the elapsed time in nanoseconds
     */
    public static void logTiming(@Nonnull String systemName, long elapsedNanos) {
        if (!globalDebugEnabled) return;
        if (pluginRef == null) return;
        double elapsedMs = elapsedNanos / 1_000_000.0;
        if (elapsedMs > 1.0) {
            // Only log timings above 1ms to avoid log spam
            pluginRef.getLogger().info(String.format("[DEBUG] [PERF] %s took %.2fms", systemName, elapsedMs));
        }
    }

    /**
     * Logs a performance timing message for a specific bot.
     *
     * @param bot          the bot
     * @param systemName   the subsystem name
     * @param elapsedNanos elapsed time in nanoseconds
     */
    public static void logTiming(@Nonnull TrainerBot bot, @Nonnull String systemName, long elapsedNanos) {
        if (!isDebugEnabled(bot)) return;
        if (pluginRef == null) return;
        double elapsedMs = elapsedNanos / 1_000_000.0;
        if (elapsedMs > 1.0) {
            pluginRef.getLogger().info(String.format("[DEBUG] [PERF] [%s] %s took %.2fms",
                    bot.getName(), systemName, elapsedMs));
        }
    }

    /**
     * Logs a warning-level debug message. These are always logged regardless
     * of debug mode because they indicate potential issues.
     *
     * @param format the format string
     * @param args   the format arguments
     */
    public static void warn(@Nonnull String format, Object... args) {
        if (pluginRef == null) return;
        pluginRef.getLogger().warning("[SWT] " + String.format(format, args));
    }

    /**
     * Logs a warning with an exception. Always logged regardless of debug mode.
     *
     * @param message   the warning message
     * @param throwable the exception
     */
    public static void warn(@Nonnull String message, @Nonnull Throwable throwable) {
        if (pluginRef == null) return;
        pluginRef.getLogger().log(Level.WARNING, "[SWT] " + message, throwable);
    }

    /**
     * Clears the plugin reference. Called on plugin disable.
     */
    public static void shutdown() {
        pluginRef = null;
        globalDebugEnabled = false;
    }
}
