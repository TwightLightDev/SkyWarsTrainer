package org.twightlight.skywarstrainer;

import org.twightlight.skywarstrainer.bot.BotManager;
import org.twightlight.skywarstrainer.config.ConfigManager;
import org.twightlight.skywarstrainer.config.DifficultyConfig;

import org.bukkit.Bukkit;
import org.bukkit.plugin.java.JavaPlugin;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.logging.Level;

/**
 * Main entry point for the SkyWarsTrainer plugin.
 *
 * <p>This plugin spawns intelligent practice bots for SkyWars servers using
 * the Citizens2 NPC API. It orchestrates all subsystems: configuration loading,
 * bot management, game state tracking, and the staggered tick loop.</p>
 *
 * <p>Lifecycle:
 * <ol>
 *   <li>{@link #onEnable()} — validates dependencies, loads configs, initializes managers, starts tick loop.</li>
 *   <li>{@link #onDisable()} — cleanly despawns all bots, saves state, cancels tasks.</li>
 * </ol>
 * </p>
 */
public final class SkyWarsTrainerPlugin extends JavaPlugin {

    /**
     * Singleton instance. Set on enable, cleared on disable.
     * All subsystems access the plugin through this rather than passing references everywhere.
     */
    private static SkyWarsTrainerPlugin instance;

    /** Manages loading and access to all YAML configuration files. */
    private ConfigManager configManager;

    /** Manages difficulty profiles loaded from difficulty.yml. */
    private DifficultyConfig difficultyConfig;

    /** Manages bot spawning, despawning, pooling, and per-bot tick distribution. */
    private BotManager botManager;

    /** The main repeating task ID for the bot tick loop. -1 if not running. */
    private int mainTickTaskId = -1;

    // ─── Lifecycle ──────────────────────────────────────────────

    @Override
    public void onEnable() {
        // 1. Set singleton instance
        instance = this;

        // 2. Validate Citizens dependency is present and enabled
        if (!validateCitizens()) {
            getLogger().severe("Citizens plugin not found or not enabled! SkyWarsTrainer requires Citizens2.");
            getLogger().severe("Download Citizens from: https://ci.citizensnpcs.co/");
            Bukkit.getPluginManager().disablePlugin(this);
            return;
        }

        // 3. Load all configuration files
        try {
            this.configManager = new ConfigManager(this);
            this.configManager.loadAll();
            getLogger().info("Configuration loaded successfully.");
        } catch (Exception e) {
            getLogger().log(Level.SEVERE, "Failed to load configuration! Disabling plugin.", e);
            Bukkit.getPluginManager().disablePlugin(this);
            return;
        }

        // 4. Initialize difficulty config (parses difficulty.yml into DifficultyProfile objects)
        try {
            this.difficultyConfig = new DifficultyConfig(this);
            this.difficultyConfig.load();
            getLogger().info("Difficulty profiles loaded: " + difficultyConfig.getLoadedCount() + " difficulties.");
        } catch (Exception e) {
            getLogger().log(Level.SEVERE, "Failed to load difficulty configuration!", e);
            Bukkit.getPluginManager().disablePlugin(this);
            return;
        }


        // 7. Initialize bot manager (handles NPC creation via Citizens)
        this.botManager = new BotManager(this);

        // 9. Start the main bot tick loop
        startTickLoop();

        getLogger().info("SkyWarsTrainer v" + getDescription().getVersion() + " enabled successfully!");
        getLogger().info("Use /swt spawn <difficulty> to create a practice bot.");

        if (configManager.isDebugMode()) {
            getLogger().info("[DEBUG] Debug mode is ON — verbose AI logging is active.");
        }
    }

    @Override
    public void onDisable() {
        // 1. Stop the tick loop
        stopTickLoop();

        // 2. Despawn and clean up all active bots
        if (botManager != null) {
            int removed = botManager.removeAllBots();
            getLogger().info("Removed " + removed + " active bot(s).");
        }

        // 3. Clear singleton
        instance = null;

        getLogger().info("SkyWarsTrainer disabled.");
    }

    // ─── Tick Loop ──────────────────────────────────────────────

    /**
     * Starts the main repeating task that drives all bot AI each server tick.
     *
     * <p>The tick rate is configurable via {@code general.bot-tick-rate} in config.yml.
     * When staggering is enabled, bots are distributed across ticks to avoid
     * processing all bots on the same tick.</p>
     */
    private void startTickLoop() {
        int tickRate = configManager.getBotTickRate();
        long maxMsPerTick = configManager.getMaxTotalMsPerTick();

        /*
         * The main loop is a synchronous repeating task because bot actions
         * (movement, block placement, entity interaction) must happen on the
         * main server thread. Heavy computations (map scanning, pathfinding)
         * are offloaded to async tasks by individual subsystems.
         */
        mainTickTaskId = Bukkit.getScheduler().scheduleSyncRepeatingTask(this, () -> {
            long startNanos = System.nanoTime();
            try {
                botManager.tickAll(startNanos, maxMsPerTick);
            } catch (Exception e) {
                getLogger().log(Level.WARNING, "Error in bot tick loop", e);
            }
        }, 1L, tickRate);

        if (mainTickTaskId == -1) {
            getLogger().severe("Failed to start bot tick loop!");
        } else {
            getLogger().info("Bot tick loop started (rate=" + tickRate + " tick(s), budget=" + maxMsPerTick + "ms).");
        }
    }

    /**
     * Stops the main bot tick loop if it is running.
     */
    private void stopTickLoop() {
        if (mainTickTaskId != -1) {
            Bukkit.getScheduler().cancelTask(mainTickTaskId);
            mainTickTaskId = -1;
            getLogger().info("Bot tick loop stopped.");
        }
    }

    // ─── Dependency Validation ──────────────────────────────────

    /**
     * Checks that the Citizens plugin is present and enabled.
     *
     * @return true if Citizens is available, false otherwise
     */
    private boolean validateCitizens() {
        /*
         * We check both that the plugin exists and that CitizensAPI is accessible.
         * Citizens must be in the 'depend' list in plugin.yml so it loads first.
         */
        if (Bukkit.getPluginManager().getPlugin("Citizens") == null) {
            return false;
        }
        try {
            // Verify the API class is loadable
            Class.forName("net.citizensnpcs.api.CitizensAPI");
            return Bukkit.getPluginManager().getPlugin("Citizens").isEnabled();
        } catch (ClassNotFoundException e) {
            return false;
        }
    }

    // ─── Accessors ──────────────────────────────────────────────

    /**
     * Returns the singleton plugin instance.
     *
     * @return the plugin instance, or null if not enabled
     */
    @Nullable
    public static SkyWarsTrainerPlugin getInstance() {
        return instance;
    }

    /**
     * Returns the configuration manager.
     *
     * @return the config manager
     */
    @Nonnull
    public ConfigManager getConfigManager() {
        return configManager;
    }

    /**
     * Returns the difficulty configuration containing all difficulty profiles.
     *
     * @return the difficulty config
     */
    @Nonnull
    public DifficultyConfig getDifficultyConfig() {
        return difficultyConfig;
    }

    /**
     * Returns the bot manager responsible for all bot lifecycle operations.
     *
     * @return the bot manager
     */
    @Nonnull
    public BotManager getBotManager() {
        return botManager;
    }
}
