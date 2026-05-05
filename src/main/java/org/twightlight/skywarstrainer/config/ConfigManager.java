package org.twightlight.skywarstrainer.config;

import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.twightlight.skywarstrainer.SkyWarsTrainer;

import javax.annotation.Nonnull;
import java.io.File;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * Manages loading and access to all YAML configuration files for SkyWarsTrainer.
 *
 * <p>Configuration files managed:
 * <ul>
 *   <li>{@code config.yml} — general plugin settings, performance tuning, skins</li>
 *   <li>{@code difficulty.yml} — difficulty level parameters (loaded by {@link DifficultyConfig})</li>
 *   <li>{@code messages.yml} — chat messages for bot communication</li>
 *   <li>{@code personalities.yml} — personality weight modifiers</li>
 * </ul></p>
 */
public class ConfigManager {

    private final SkyWarsTrainer plugin;

    /** The main config.yml file configuration. */
    private FileConfiguration mainConfig;

    /** The difficulty.yml file configuration (accessed by DifficultyConfig). */
    private FileConfiguration difficultyFileConfig;

    /** The messages.yml file configuration. */
    private FileConfiguration messagesConfig;

    // ═══════════════════════════════════════════════════════════
    //  CACHED VALUES — general
    // ═══════════════════════════════════════════════════════════
    private int maxBotsPerGame;
    private int botTickRate;
    private int decisionInterval;
    private int mapScanInterval;
    private String defaultDifficulty;
    private int defaultPersonalityCount;
    private boolean enableChat;
    private boolean enableSkins;
    private boolean debugMode;
    private int botWarmupSeconds;

    // ═══════════════════════════════════════════════════════════
    //  CACHED VALUES — performance
    // ═══════════════════════════════════════════════════════════
    private long maxPathfindingMsPerTick;
    private boolean staggerBotTicks;
    private boolean cacheMapScan;
    private long maxTotalMsPerTick;
    private long subsystemTimeoutMs;
    private int perfLogInterval;

    // ═══════════════════════════════════════════════════════════
    //  CACHED VALUES — skins (v2)
    // ═══════════════════════════════════════════════════════════
    private boolean skinsEnabled;
    private String skinsMode;
    private boolean skinsRandomSelection;
    private boolean skinsCacheEnabled;
    private int skinsCacheMaxEntries;
    private int skinsCacheExpiryHours;

    // ═══════════════════════════════════════════════════════════
    //  CACHED VALUES — random names (v2)
    // ═══════════════════════════════════════════════════════════
    private String nameStrategy;
    private List<String> compositeNamePrefixes;
    private List<String> compositeNameRoots;
    private List<String> compositeNameSuffixes;
    private int nameMaxLength;
    private double nameLeetChance;
    private double nameRandomCapsChance;
    private double nameUnderscoreSeparatorChance;

    // ═══════════════════════════════════════════════════════════
    //  CACHED VALUES — chat (v2)
    // ═══════════════════════════════════════════════════════════
    private int chatCooldownSeconds;
    private int chatTypingSpeedMinMs;
    private int chatTypingSpeedMaxMs;
    private int chatMaxDelayTicks;
    private boolean chatShowTypingParticles;
    private double chatChanceGameStart;
    private double chatChanceFirstKill;
    private double chatChanceDeath;
    private double chatChanceWin;
    private double chatChanceCloseFightWon;
    private double chatChanceCloseFightLost;

    // ═══════════════════════════════════════════════════════════
    //  CACHED VALUES — tick timers
    // ═══════════════════════════════════════════════════════════
    private int timerVoidDetectInterval;
    private int timerLavaDetectInterval;
    private int timerChestUpdateInterval;
    private int timerIslandGraphInterval;
    private int timerGamePhaseInterval;
    private int timerBehaviorTreeInterval;
    private int timerInventoryAuditInterval;
    private int timerPositionalInterval;
    private int timerEnemyAnalyzerInterval;

    public ConfigManager(@Nonnull SkyWarsTrainer plugin) {
        this.plugin = plugin;
    }

    public void loadAll() {
        plugin.saveDefaultConfig();
        saveResourceIfMissing("difficulty.yml");
        saveResourceIfMissing("messages.yml");
        saveResourceIfMissing("personalities.yml");

        plugin.reloadConfig();
        this.mainConfig = plugin.getConfig();

        this.difficultyFileConfig = loadYamlFile("difficulty.yml");
        this.messagesConfig = loadYamlFile("messages.yml");

        cacheMainConfigValues();

        plugin.getLogger().info("All configuration files loaded.");
    }

    private void saveResourceIfMissing(@Nonnull String resourceName) {
        File file = new File(plugin.getDataFolder(), resourceName);
        if (!file.exists()) {
            plugin.saveResource(resourceName, false);
            plugin.getLogger().info("Created default " + resourceName);
        }
    }

    @Nonnull
    private FileConfiguration loadYamlFile(@Nonnull String fileName) {
        File file = new File(plugin.getDataFolder(), fileName);
        FileConfiguration config = YamlConfiguration.loadConfiguration(file);

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
     * [FIX] All paths now match the actual config.yml v2 structure exactly.
     */
    private void cacheMainConfigValues() {
        // ── General ──
        this.maxBotsPerGame = mainConfig.getInt("general.max-bots-per-game", 8);
        this.botTickRate = mainConfig.getInt("general.bot-tick-rate", 1);
        this.decisionInterval = mainConfig.getInt("general.decision-interval", 10);
        this.mapScanInterval = mainConfig.getInt("general.map-scan-interval", 50);
        this.defaultDifficulty = mainConfig.getString("general.default-difficulty", "MEDIUM");
        this.defaultPersonalityCount = mainConfig.getInt("general.default-personality-count", 2);
        this.enableChat = mainConfig.getBoolean("general.enable-chat", true);
        this.enableSkins = mainConfig.getBoolean("general.enable-skins", true);
        this.debugMode = mainConfig.getBoolean("general.debug-mode", false);
        this.botWarmupSeconds = mainConfig.getInt("general.bot-warmup-seconds", 0);

        // ── Performance ──
        this.maxPathfindingMsPerTick = mainConfig.getLong("performance.max-pathfinding-ms-per-tick", 2);
        this.staggerBotTicks = mainConfig.getBoolean("performance.stagger-bot-ticks", true);
        this.cacheMapScan = mainConfig.getBoolean("performance.cache-map-scan", true);
        this.maxTotalMsPerTick = mainConfig.getLong("performance.max-total-ms-per-tick", 8);
        this.subsystemTimeoutMs = mainConfig.getLong("performance.subsystem-timeout-ms", 3);
        this.perfLogInterval = mainConfig.getInt("performance.perf-log-interval", 0);

        // ── Skins v2 ── [FIX: was reading nonexistent skins.use-random-skins / skins.skin-list]
        this.skinsEnabled = mainConfig.getBoolean("skins.enabled", true);
        this.skinsMode = mainConfig.getString("skins.mode", "MIXED");
        this.skinsRandomSelection = mainConfig.getBoolean("skins.random-selection", true);
        this.skinsCacheEnabled = mainConfig.getBoolean("skins.cache.enabled", true);
        this.skinsCacheMaxEntries = mainConfig.getInt("skins.cache.max-entries", 200);
        this.skinsCacheExpiryHours = mainConfig.getInt("skins.cache.expiry-hours", 72);

        // ── Random Names v2 ── [FIX: was reading random-names.prefixes instead of random-names.composite.prefixes]
        this.nameStrategy = mainConfig.getString("random-names.strategy", "MIXED");
        this.compositeNamePrefixes = mainConfig.getStringList("random-names.composite.prefixes");
        this.compositeNameRoots = mainConfig.getStringList("random-names.composite.roots");
        this.compositeNameSuffixes = mainConfig.getStringList("random-names.composite.suffixes");
        this.nameMaxLength = mainConfig.getInt("random-names.formatting.max-length", 16);
        this.nameLeetChance = mainConfig.getDouble("random-names.formatting.leet-chance", 0.05);
        this.nameRandomCapsChance = mainConfig.getDouble("random-names.formatting.random-caps-chance", 0.03);
        this.nameUnderscoreSeparatorChance = mainConfig.getDouble("random-names.formatting.underscore-separator-chance", 0.15);

        // ── Chat v2 ── [FIX: was ignoring message-chance and show-typing-particles]
        this.chatCooldownSeconds = mainConfig.getInt("chat.cooldown-seconds", 5);
        this.chatTypingSpeedMinMs = mainConfig.getInt("chat.typing-speed-min-ms", 20);
        this.chatTypingSpeedMaxMs = mainConfig.getInt("chat.typing-speed-max-ms", 80);
        this.chatMaxDelayTicks = mainConfig.getInt("chat.max-delay-ticks", 80);
        this.chatShowTypingParticles = mainConfig.getBoolean("chat.show-typing-particles", false);
        this.chatChanceGameStart = mainConfig.getDouble("chat.message-chance.game_start", 0.7);
        this.chatChanceFirstKill = mainConfig.getDouble("chat.message-chance.first_kill", 0.5);
        this.chatChanceDeath = mainConfig.getDouble("chat.message-chance.death", 0.6);
        this.chatChanceWin = mainConfig.getDouble("chat.message-chance.win", 0.9);
        this.chatChanceCloseFightWon = mainConfig.getDouble("chat.message-chance.close_fight_won", 0.3);
        this.chatChanceCloseFightLost = mainConfig.getDouble("chat.message-chance.close_fight_lost", 0.3);

        // ── Tick Timers ── [FIX: these were entirely missing — hardcoded in TrainerBot]
        this.timerVoidDetectInterval = mainConfig.getInt("timers.void-detect-interval", 5);
        this.timerLavaDetectInterval = mainConfig.getInt("timers.lava-detect-interval", 15);
        this.timerChestUpdateInterval = mainConfig.getInt("timers.chest-update-interval", 60);
        this.timerIslandGraphInterval = mainConfig.getInt("timers.island-graph-interval", 200);
        this.timerGamePhaseInterval = mainConfig.getInt("timers.game-phase-interval", 30);
        this.timerBehaviorTreeInterval = mainConfig.getInt("timers.behavior-tree-interval", 3);
        this.timerInventoryAuditInterval = mainConfig.getInt("timers.inventory-audit-interval", 100);
        this.timerPositionalInterval = mainConfig.getInt("timers.positional-interval", 50);
        this.timerEnemyAnalyzerInterval = mainConfig.getInt("timers.enemy-analyzer-interval", 20);
    }

    // ─── Reloading ──────────────────────────────────────────────

    public void reload() {
        loadAll();
        plugin.getLogger().info("Configuration reloaded.");
    }

    // ═══════════════════════════════════════════════════════════
    //  GETTERS — General
    // ═══════════════════════════════════════════════════════════

    public int getMaxBotsPerGame() { return maxBotsPerGame; }
    public int getBotTickRate() { return botTickRate; }
    public int getDecisionInterval() { return decisionInterval; }
    public int getMapScanInterval() { return mapScanInterval; }
    @Nonnull public String getDefaultDifficulty() { return defaultDifficulty; }
    public int getDefaultPersonalityCount() { return defaultPersonalityCount; }
    public boolean isChatEnabled() { return enableChat; }
    public boolean isSkinsEnabled() { return enableSkins && skinsEnabled; }
    public boolean isDebugMode() { return debugMode; }
    public int getBotWarmupSeconds() { return botWarmupSeconds; }

    // ═══════════════════════════════════════════════════════════
    //  GETTERS — Performance
    // ═══════════════════════════════════════════════════════════

    public long getMaxPathfindingMsPerTick() { return maxPathfindingMsPerTick; }
    public boolean isStaggerBotTicks() { return staggerBotTicks; }
    public boolean isCacheMapScan() { return cacheMapScan; }
    public long getMaxTotalMsPerTick() { return maxTotalMsPerTick; }
    public long getSubsystemTimeoutMs() { return subsystemTimeoutMs; }
    public int getPerfLogInterval() { return perfLogInterval; }

    // ═══════════════════════════════════════════════════════════
    //  GETTERS — Skins v2
    // ═══════════════════════════════════════════════════════════

    /** @return the skin resolution mode (USERNAME, TEXTURE, MIXED) */
    @Nonnull public String getSkinsMode() { return skinsMode; }

    /** @return whether to randomly select skins (true) or round-robin (false) */
    public boolean isSkinsRandomSelection() { return skinsRandomSelection; }

    /** @return whether skin texture caching is enabled */
    public boolean isSkinsCacheEnabled() { return skinsCacheEnabled; }

    /** @return max cached skin entries */
    public int getSkinsCacheMaxEntries() { return skinsCacheMaxEntries; }

    /** @return hours before cached skin textures expire */
    public int getSkinsCacheExpiryHours() { return skinsCacheExpiryHours; }

    // Legacy compat — delegates to v2 skins
    /** @deprecated use isSkinsRandomSelection() */
    public boolean isUseRandomSkins() { return skinsRandomSelection; }

    /** @deprecated use skin pool resolution in BotSkin */
    @Nonnull public List<String> getSkinList() {
        return mainConfig.getStringList("skins.global-pool.usernames");
    }

    // ═══════════════════════════════════════════════════════════
    //  GETTERS — Name Generation v2
    // ═══════════════════════════════════════════════════════════

    /** @return name generation strategy (COMPOSITE, REALISTIC, FIXED_POOL, MIXED) */
    @Nonnull public String getNameStrategy() { return nameStrategy; }

    /** @return composite prefixes from random-names.composite.prefixes */
    @Nonnull public List<String> getNamePrefixes() { return compositeNamePrefixes; }

    /** @return composite roots from random-names.composite.roots */
    @Nonnull public List<String> getNameRoots() { return compositeNameRoots; }

    /** @return composite suffixes from random-names.composite.suffixes */
    @Nonnull public List<String> getNameSuffixes() { return compositeNameSuffixes; }

    /** @return max name length from random-names.formatting.max-length */
    public int getNameMaxLength() { return nameMaxLength; }

    /** @return leet-speak chance from random-names.formatting.leet-chance */
    public double getNameLeetChance() { return nameLeetChance; }

    /** @return random caps chance */
    public double getNameRandomCapsChance() { return nameRandomCapsChance; }

    /** @return underscore separator chance */
    public double getNameUnderscoreSeparatorChance() { return nameUnderscoreSeparatorChance; }

    // ═══════════════════════════════════════════════════════════
    //  GETTERS — Chat v2
    // ═══════════════════════════════════════════════════════════

    /** @return chat cooldown in seconds between messages from the same bot */
    public int getChatCooldownSeconds() { return chatCooldownSeconds; }

    /** @return min typing speed in ms per character */
    public int getChatTypingSpeedMinMs() { return chatTypingSpeedMinMs; }

    /** @return max typing speed in ms per character */
    public int getChatTypingSpeedMaxMs() { return chatTypingSpeedMaxMs; }

    /** @return max message delay in ticks */
    public int getChatMaxDelayTicks() { return chatMaxDelayTicks; }

    /** @return whether to show typing indicator particles */
    public boolean isChatShowTypingParticles() { return chatShowTypingParticles; }

    /** @return message chance for game_start event */
    public double getChatChanceGameStart() { return chatChanceGameStart; }

    /** @return message chance for first_kill event */
    public double getChatChanceFirstKill() { return chatChanceFirstKill; }

    /** @return message chance for death event */
    public double getChatChanceDeath() { return chatChanceDeath; }

    /** @return message chance for win event */
    public double getChatChanceWin() { return chatChanceWin; }

    /** @return message chance for close_fight_won event */
    public double getChatChanceCloseFightWon() { return chatChanceCloseFightWon; }

    /** @return message chance for close_fight_lost event */
    public double getChatChanceCloseFightLost() { return chatChanceCloseFightLost; }

    /**
     * Returns the message chance for a given event type key.
     * Falls back to 0.5 if the event type is not configured.
     *
     * @param eventType the event type key (e.g., "game_start", "death")
     * @return the chance [0.0, 1.0]
     */
    public double getChatChanceForEvent(@Nonnull String eventType) {
        switch (eventType) {
            case "game_start": return chatChanceGameStart;
            case "first_kill": return chatChanceFirstKill;
            case "kill": return 0.3;
            case "death": return chatChanceDeath;
            case "win": return chatChanceWin;
            case "close_fight_won": return chatChanceCloseFightWon;
            case "close_fight_lost": return chatChanceCloseFightLost;
            default: return 0.5;
        }
    }

    // ═══════════════════════════════════════════════════════════
    //  GETTERS — Tick Timers
    // ═══════════════════════════════════════════════════════════

    public int getTimerVoidDetectInterval() { return timerVoidDetectInterval; }
    public int getTimerLavaDetectInterval() { return timerLavaDetectInterval; }
    public int getTimerChestUpdateInterval() { return timerChestUpdateInterval; }
    public int getTimerIslandGraphInterval() { return timerIslandGraphInterval; }
    public int getTimerGamePhaseInterval() { return timerGamePhaseInterval; }
    public int getTimerBehaviorTreeInterval() { return timerBehaviorTreeInterval; }
    public int getTimerInventoryAuditInterval() { return timerInventoryAuditInterval; }
    public int getTimerPositionalInterval() { return timerPositionalInterval; }
    public int getTimerEnemyAnalyzerInterval() { return timerEnemyAnalyzerInterval; }

    // ═══════════════════════════════════════════════════════════
    //  RAW CONFIG ACCESS
    // ═══════════════════════════════════════════════════════════

    @Nonnull public FileConfiguration getMainConfig() { return mainConfig; }
    @Nonnull public FileConfiguration getDifficultyFileConfig() { return difficultyFileConfig; }
    @Nonnull public FileConfiguration getMessagesConfig() { return messagesConfig; }
}
