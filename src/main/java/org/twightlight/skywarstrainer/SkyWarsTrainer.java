package org.twightlight.skywarstrainer;

import org.twightlight.skywarstrainer.ai.learning.*;
import org.twightlight.skywarstrainer.api.SkyWarsTrainerAPI;
import org.twightlight.skywarstrainer.bot.BotManager;
import org.twightlight.skywarstrainer.bot.TrainerBot;
import org.twightlight.skywarstrainer.commands.CommandHandler;
import org.twightlight.skywarstrainer.commands.TabCompleter;
import org.twightlight.skywarstrainer.config.ConfigManager;
import org.twightlight.skywarstrainer.config.DifficultyConfig;
import org.twightlight.skywarstrainer.config.PersonalityConfig;
import org.twightlight.skywarstrainer.game.BotChatManager;
import org.twightlight.skywarstrainer.game.GameEventListener;

import net.citizensnpcs.api.CitizensAPI;
import net.citizensnpcs.api.trait.TraitInfo;

import org.bukkit.Bukkit;
import org.bukkit.plugin.java.JavaPlugin;
import org.twightlight.skywarstrainer.util.DebugLogger;

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
public final class SkyWarsTrainer extends JavaPlugin {

    private static SkyWarsTrainer instance;

    private ConfigManager configManager;
    private DifficultyConfig difficultyConfig;
    private PersonalityConfig personalityConfig;
    private BotManager botManager;
    private GameEventListener gameEventListener;
    private SkyWarsTrainerAPI api;
    private CommandHandler commandHandler;
    private LearningManager learningManager;
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

        // 2. Register the Citizens trait
        try {
            CitizensAPI.getTraitFactory().registerTrait(
                    TraitInfo.create(TrainerBot.SkyWarsTrainerTrait.class)
                            .withName(TrainerBot.SkyWarsTrainerTrait.TRAIT_NAME));
            getLogger().info("Registered SkyWarsTrainer trait with Citizens.");
        } catch (Exception e) {
            getLogger().log(Level.WARNING, "Could not register trait with Citizens (may already exist).", e);
        }

        // 3. Load configs
        try {
            this.configManager = new ConfigManager(this);
            this.configManager.loadAll();
            getLogger().info("Configuration loaded successfully.");
        } catch (Exception e) {
            getLogger().log(Level.SEVERE, "Failed to load configuration!", e);
            Bukkit.getPluginManager().disablePlugin(this);
            return;
        }

        // 4. Load difficulty profiles
        try {
            this.difficultyConfig = new DifficultyConfig(this);
            this.difficultyConfig.load();
            getLogger().info("Difficulty profiles loaded: " + difficultyConfig.getLoadedCount());
        } catch (Exception e) {
            getLogger().log(Level.SEVERE, "Failed to load difficulty configuration!", e);
            Bukkit.getPluginManager().disablePlugin(this);
            return;
        }

        // 5. Load personality config
        try {
            this.personalityConfig = new PersonalityConfig(this);
            this.personalityConfig.load();
            getLogger().info("Personality configuration loaded.");
        } catch (Exception e) {
            getLogger().log(Level.WARNING, "Failed to load personality config, using defaults.", e);
            this.personalityConfig = new PersonalityConfig(this);
        }

        this.learningManager = new LearningManager(this);

        // 6. Initialize bot manager
        this.botManager = new BotManager(this);

        // 7. Initialize API
        this.api = new SkyWarsTrainerAPI(this);

        // 8. Register event listener
        this.gameEventListener = new GameEventListener(this);
        Bukkit.getPluginManager().registerEvents(gameEventListener, this);
        getLogger().info("Game event listener registered.");

        // 9. Register commands
        this.commandHandler = new CommandHandler(this);
        if (getCommand("swt") != null) {
            getCommand("swt").setExecutor(commandHandler);
            getCommand("swt").setTabCompleter(new TabCompleter(this));
            getLogger().info("Commands registered.");
        } else {
            getLogger().severe("Failed to register /swt command! Check plugin.yml.");
        }

        getLogger().info("SkyWarsTrainer v" + getDescription().getVersion() + " enabled successfully!");
        getLogger().info("Use /swt spawn <difficulty> to create a practice bot.");

        DebugLogger.init(this);

        if (configManager.isDebugMode()) {
            getLogger().info("[DEBUG] Debug mode is ON.");
        }
    }

    @Override
    public void onDisable() {
        if (botManager != null) {
            int removed = botManager.removeAllBots();
            learningManager.shutdown();
            getLogger().info("Removed " + removed + " active bot(s).");
        }

        // Clear chat manager cooldowns
        BotChatManager.clearAllCooldowns();

        SkyWarsTrainerAPI.clearInstance();
        instance = null;
        getLogger().info("SkyWarsTrainer disabled.");
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
    public static SkyWarsTrainer getInstance() {
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
    public PersonalityConfig getPersonalityConfig() {
        return personalityConfig;
    }

    @Nonnull
    public BotManager getBotManager() {
        return botManager;
    }

    @Nonnull
    public GameEventListener getGameEventListener() {
        return gameEventListener;
    }

    @Nullable
    public SkyWarsTrainerAPI getApi() {
        return api;
    }

    public LearningManager getLearningManager() {
        return learningManager;
    }
}
