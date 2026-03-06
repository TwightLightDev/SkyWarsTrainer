package org.twightlight.skywarstrainer;

import org.twightlight.skywarstrainer.bot.BotManager;
import org.twightlight.skywarstrainer.config.ConfigManager;
import org.twightlight.skywarstrainer.config.DifficultyConfig;
import org.twightlight.skywarstrainer.game.GameEventListener;

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
 * bot management, game state tracking, event listening, and the staggered tick loop.</p>
 */
public final class SkyWarsTrainerPlugin extends JavaPlugin {

    private static SkyWarsTrainerPlugin instance;

    private ConfigManager configManager;
    private DifficultyConfig difficultyConfig;
    private BotManager botManager;
    private GameEventListener gameEventListener;
    private int mainTickTaskId = -1;

    // ─── Lifecycle ──────────────────────────────────────────────

    @Override
    public void onEnable() {
        instance = this;

        // 1. Validate Citizens
        if (!validateCitizens()) {
            getLogger().severe("Citizens plugin not found or not enabled! SkyWarsTrainer requires Citizens2.");
            Bukkit.getPluginManager().disablePlugin(this);
            return;
        }

        // 2. Load configs
        try {
            this.configManager = new ConfigManager(this);
            this.configManager.loadAll();
            getLogger().info("Configuration loaded successfully.");
        } catch (Exception e) {
            getLogger().log(Level.SEVERE, "Failed to load configuration!", e);
            Bukkit.getPluginManager().disablePlugin(this);
            return;
        }

        // 3. Load difficulty profiles
        try {
            this.difficultyConfig = new DifficultyConfig(this);
            this.difficultyConfig.load();
            getLogger().info("Difficulty profiles loaded: " + difficultyConfig.getLoadedCount());
        } catch (Exception e) {
            getLogger().log(Level.SEVERE, "Failed to load difficulty configuration!", e);
            Bukkit.getPluginManager().disablePlugin(this);
            return;
        }

        // 4. Initialize bot manager
        this.botManager = new BotManager(this);


        // 6. Register event listener for LostSkyWars + Bukkit events
        this.gameEventListener = new GameEventListener(this);
        Bukkit.getPluginManager().registerEvents(gameEventListener, this);
        getLogger().info("Game event listener registered.");

        // 7. Start the main bot tick loop
        startTickLoop();

        getLogger().info("SkyWarsTrainer v" + getDescription().getVersion() + " enabled successfully!");
        getLogger().info("Use /swt spawn <difficulty> to create a practice bot.");

        if (configManager.isDebugMode()) {
            getLogger().info("[DEBUG] Debug mode is ON.");
        }
    }

    @Override
    public void onDisable() {
        stopTickLoop();

        if (botManager != null) {
            int removed = botManager.removeAllBots();
            getLogger().info("Removed " + removed + " active bot(s).");
        }

        instance = null;
        getLogger().info("SkyWarsTrainer disabled.");
    }

    // ─── Tick Loop ──────────────────────────────────────────────

    private void startTickLoop() {
        int tickRate = configManager.getBotTickRate();
        long maxMsPerTick = configManager.getMaxTotalMsPerTick();

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
            getLogger().info("Bot tick loop started (rate=" + tickRate + ", budget=" + maxMsPerTick + "ms).");
        }
    }

    private void stopTickLoop() {
        if (mainTickTaskId != -1) {
            Bukkit.getScheduler().cancelTask(mainTickTaskId);
            mainTickTaskId = -1;
        }
    }

    // ─── Dependency Validation ──────────────────────────────────

    private boolean validateCitizens() {
        if (Bukkit.getPluginManager().getPlugin("Citizens") == null) return false;
        try {
            Class.forName("net.citizensnpcs.api.CitizensAPI");
            return Bukkit.getPluginManager().getPlugin("Citizens").isEnabled();
        } catch (ClassNotFoundException e) {
            return false;
        }
    }

    // ─── Accessors ──────────────────────────────────────────────

    @Nullable
    public static SkyWarsTrainerPlugin getInstance() {
        return instance;
    }

    @Nonnull
    public ConfigManager getConfigManager() {
        return configManager;
    }

    @Nonnull
    public DifficultyConfig getDifficultyConfig() {
        return difficultyConfig;
    }

    @Nonnull
    public BotManager getBotManager() {
        return botManager;
    }

    /**
     * Returns the game event listener.
     *
     * @return the event listener
     */
    @Nonnull
    public GameEventListener getGameEventListener() {
        return gameEventListener;
    }
}