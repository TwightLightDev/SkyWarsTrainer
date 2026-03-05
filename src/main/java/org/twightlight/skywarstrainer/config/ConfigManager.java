package org.twightlight.skywarstrainer.config;

import org.twightlight.skywarstrainer.SkyWarsTrainerPlugin;

import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.logging.Level;

/**
 * Manages loading and access to all YAML configuration files for SkyWarsTrainer.
 *
 * <p>Configuration files managed:
 * <ul>
 *   <li>{@code config.yml} — general plugin settings, performance tuning, skins</li>
 *   <li>{@code difficulty.yml} — difficulty level parameters (loaded by {@link DifficultyConfig})</li>
 *   <li>{@code messages.yml} — chat messages for bot communication (loaded later in Phase 6)</li>
 *   <li>{@code personalities.yml} — personality weight modifiers (loaded later in Phase 6)</li>
 * </ul></p>
 *
 * <p>Files are saved from the JAR's resources on first run, then loaded from disk.
 * Missing keys fall back to defaults embedded in the JAR resource.</p>
 */
public class ConfigManager {

    private final SkyWarsTrainerPlugin plugin;

    /** The main config.yml file configuration. */
    private FileConfiguration mainConfig;

    /** The difficulty.yml file configuration (accessed by DifficultyConfig). */
    private FileConfiguration difficultyFileConfig;

    /** The messages.yml file configuration. */
    private FileConfiguration messagesConfig;

    /** Cached values from config.yml for fast access without repeated YAML lookups. */
    private int maxBotsPerGame;
    private int botTickRate;
    private int decisionInterval;
    private int mapScanInterval;
    private String defaultDifficulty;
    private int defaultPersonalityCount;
    private boolean enableChat;
    private boolean enableSkins;
    private boolean debugMode;
    private long maxPathfindingMsPerTick;
    private boolean staggerBotTicks;
    private boolean cacheMapScan;
    private long maxTotalMsPerTick;
    private String gameHookPlugin;
    private boolean useRandomSkins;
    private List<String> skinList;
    private List<String> namePrefixes;
    private List<String> nameRoots;
    private List<String> nameSuffixes;

    /**
     * Creates a new ConfigManager for the given plugin.
     *
     * @param plugin the owning plugin instance
     */
    public ConfigManager(@Nonnull SkyWarsTrainerPlugin plugin) {
        this.plugin = plugin;
    }

    /**
     * Loads (or reloads) all configuration files. Call this once on plugin enable
     * and whenever a config reload is requested.
     *
     * <p>Process:
     * <ol>
     *   <li>Save default resources from JAR if files don't exist on disk.</li>
     *   <li>Load each YAML file.</li>
     *   <li>Cache frequently accessed values for performance.</li>
     * </ol></p>
     */
    public void loadAll() {
        // Save defaults from JAR resources if they don't exist
        plugin.saveDefaultConfig();
        saveResourceIfMissing("difficulty.yml");
        saveResourceIfMissing("messages.yml");

        // Load main config
        plugin.reloadConfig();
        this.mainConfig = plugin.getConfig();

        // Load difficulty config file
        this.difficultyFileConfig = loadYamlFile("difficulty.yml");

        // Load messages config file
        this.messagesConfig = loadYamlFile("messages.yml");

        // Cache values for fast access
        cacheMainConfigValues();

        plugin.getLogger().info("All configuration files loaded.");
    }

    /**
     * Saves a resource file from the JAR to the plugin data folder if it
     * doesn't already exist on disk.
     *
     * @param resourceName the resource file name (e.g., "difficulty.yml")
     */
    private void saveResourceIfMissing(@Nonnull String resourceName) {
        File file = new File(plugin.getDataFolder(), resourceName);
        if (!file.exists()) {
            plugin.saveResource(resourceName, false);
            plugin.getLogger().info("Created default " + resourceName);
        }
    }

    /**
     * Loads a YAML file from the plugin's data folder. If the file doesn't exist
     * or fails to parse, falls back to the default resource embedded in the JAR.
     *
     * @param fileName the file name relative to the plugin data folder
     * @return the loaded FileConfiguration (never null; may be empty on total failure)
     */
    @Nonnull
    private FileConfiguration loadYamlFile(@Nonnull String fileName) {
        File file = new File(plugin.getDataFolder(), fileName);
        FileConfiguration config = YamlConfiguration.loadConfiguration(file);

        /*
         * Merge in defaults from the JAR resource so that new keys added in
         * plugin updates are available even if the user hasn't updated their file.
         */
        InputStream defaultStream = plugin.getResource(fileName);
        if (defaultStream != null) {
            YamlConfiguration defaultConfig = YamlConfiguration.loadConfiguration(
                    new InputStreamReader(defaultStream, StandardCharsets.UTF_8)
            );
            config.setDefaults(defaultConfig);
        }

        return config;
    }

    /**
     * Caches frequently accessed values from config.yml into fields.
     * This avoids repeated string-key lookups in hot paths.
     */
    private void cacheMainConfigValues() {
        this.maxBotsPerGame = mainConfig.getInt("general.max-bots-per-game", 8);
        this.botTickRate = mainConfig.getInt("general.bot-tick-rate", 1);
        this.decisionInterval = mainConfig.getInt("general.decision-interval", 10);
        this.mapScanInterval = mainConfig.getInt("general.map-scan-interval", 50);
        this.defaultDifficulty = mainConfig.getString("general.default-difficulty", "MEDIUM");
        this.defaultPersonalityCount = mainConfig.getInt("general.default-personality-count", 2);
        this.enableChat = mainConfig.getBoolean("general.enable-chat", true);
        this.enableSkins = mainConfig.getBoolean("general.enable-skins", true);
        this.debugMode = mainConfig.getBoolean("general.debug-mode", false);

        this.maxPathfindingMsPerTick = mainConfig.getLong("performance.max-pathfinding-ms-per-tick", 2);
        this.staggerBotTicks = mainConfig.getBoolean("performance.stagger-bot-ticks", true);
        this.cacheMapScan = mainConfig.getBoolean("performance.cache-map-scan", true);
        this.maxTotalMsPerTick = mainConfig.getLong("performance.max-total-ms-per-tick", 8);

        this.gameHookPlugin = mainConfig.getString("game-hook.plugin", "auto");

        this.useRandomSkins = mainConfig.getBoolean("skins.use-random-skins", true);
        this.skinList = mainConfig.getStringList("skins.skin-list");

        this.namePrefixes = mainConfig.getStringList("random-names.prefixes");
        this.nameRoots = mainConfig.getStringList("random-names.roots");
        this.nameSuffixes = mainConfig.getStringList("random-names.suffixes");
    }

    // ─── Reloading ──────────────────────────────────────────────

    /**
     * Reloads all configuration files from disk.
     * Called when an admin uses a reload command.
     */
    public void reload() {
        loadAll();
        // DifficultyConfig must also be reloaded (caller's responsibility)
        plugin.getLogger().info("Configuration reloaded.");
    }

    // ─── Accessors: General ─────────────────────────────────────

    /** @return maximum number of bots allowed per game */
    public int getMaxBotsPerGame() {
        return maxBotsPerGame;
    }

    /** @return how often (in ticks) the main bot loop runs */
    public int getBotTickRate() {
        return botTickRate;
    }

    /** @return ticks between utility AI re-evaluations */
    public int getDecisionInterval() {
        return decisionInterval;
    }

    /** @return ticks between full map scans */
    public int getMapScanInterval() {
        return mapScanInterval;
    }

    /** @return default difficulty name (e.g., "MEDIUM") */
    @Nonnull
    public String getDefaultDifficulty() {
        return defaultDifficulty;
    }

    /** @return default number of personalities for randomly generated bots */
    public int getDefaultPersonalityCount() {
        return defaultPersonalityCount;
    }

    /** @return whether bots send fake chat messages */
    public boolean isChatEnabled() {
        return enableChat;
    }

    /** @return whether bots use player skins */
    public boolean isSkinsEnabled() {
        return enableSkins;
    }

    /** @return whether debug mode is active */
    public boolean isDebugMode() {
        return debugMode;
    }

    // ─── Accessors: Performance ─────────────────────────────────

    /** @return max milliseconds per tick for pathfinding calculations */
    public long getMaxPathfindingMsPerTick() {
        return maxPathfindingMsPerTick;
    }

    /** @return whether bot ticks are staggered across server ticks */
    public boolean isStaggerBotTicks() {
        return staggerBotTicks;
    }

    /** @return whether map scan results should be cached */
    public boolean isCacheMapScan() {
        return cacheMapScan;
    }

    /** @return max total milliseconds per tick for all bot processing */
    public long getMaxTotalMsPerTick() {
        return maxTotalMsPerTick;
    }

    // ─── Accessors: Game Hook ───────────────────────────────────

    /** @return the configured game hook plugin name or "auto" */
    @Nonnull
    public String getGameHookPlugin() {
        return gameHookPlugin;
    }

    // ─── Accessors: Skins ───────────────────────────────────────

    /** @return whether to use random skins from the pool */
    public boolean isUseRandomSkins() {
        return useRandomSkins;
    }

    /** @return the list of skin usernames for bot appearances */
    @Nonnull
    public List<String> getSkinList() {
        return skinList;
    }

    // ─── Accessors: Name Generation ─────────────────────────────

    /** @return prefix options for random bot names */
    @Nonnull
    public List<String> getNamePrefixes() {
        return namePrefixes;
    }

    /** @return root word options for random bot names */
    @Nonnull
    public List<String> getNameRoots() {
        return nameRoots;
    }

    /** @return suffix options for random bot names */
    @Nonnull
    public List<String> getNameSuffixes() {
        return nameSuffixes;
    }

    // ─── Raw Config Access ──────────────────────────────────────

    /**
     * Returns the raw main config.yml FileConfiguration for direct access
     * to keys not cached in this class.
     *
     * @return the main config
     */
    @Nonnull
    public FileConfiguration getMainConfig() {
        return mainConfig;
    }

    /**
     * Returns the difficulty.yml FileConfiguration.
     * Used by {@link DifficultyConfig} to parse difficulty profiles.
     *
     * @return the difficulty config
     */
    @Nonnull
    public FileConfiguration getDifficultyFileConfig() {
        return difficultyFileConfig;
    }

    /**
     * Returns the messages.yml FileConfiguration.
     * Used by the chat system (Phase 6) to load personality-specific messages.
     *
     * @return the messages config
     */
    @Nonnull
    public FileConfiguration getMessagesConfig() {
        return messagesConfig;
    }
}
