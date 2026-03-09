package org.twightlight.skywarstrainer.ai.learning;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.twightlight.skywarstrainer.SkyWarsTrainer;

import javax.annotation.Nonnull;
import java.io.File;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;

/**
 * Holds all tunable hyperparameters for the reinforcement learning system.
 * Loaded from {@code learning_config.yml} in the plugin data folder.
 *
 * <p>Every numeric constant used anywhere in the learning system MUST come
 * from this config, never from a hardcoded literal. This allows tuning
 * without recompiling.</p>
 */
public class LearningConfig {

    private final SkyWarsTrainer plugin;

    // ── Learning ──
    private boolean enabled;
    private double learningRate;
    private double discountFactor;
    private double weightSensitivity;
    private double maxAdjustmentMultiplier;
    private double minAdjustmentMultiplier;
    private double lambda;
    private double tracePruneThreshold;
    private int maxGamesBeforeStable;
    private double stabilityLrMultiplier;

    // ── Memory ──
    private int maxEntries;
    private int binsPerDimension;
    private int recencyHalfLifeGames;
    private int minVisitsForConfidence;
    private int confidencePatienceGames;
    private int contradictionLookback;
    private double contradictionThreshold;
    private double consolidationSimilarity;

    // ── Replay ──
    private boolean replayEnabled;
    private int bufferCapacity;
    private int miniBatchSize;
    private int replayRoundsPerGame;
    private double priorityAlpha;
    private double importanceSamplingBetaStart;
    private double importanceSamplingBetaEnd;
    private int betaAnnealGames;
    private double priorityEpsilon;
    private int maxExperienceAgeGames;

    // ── Rewards ──
    private double rewardKill;
    private double rewardWin;
    private double rewardDeath;
    private double rewardLose;
    private double rewardWeaponUpgrade;
    private double rewardArmorUpgrade;
    private double rewardHealthGainedPerHeart;
    private double rewardHealthLostPerHeart;
    private double rewardChestLooted;
    private double rewardEnemyFled;
    private double rewardForcedToFlee;
    private double rewardBridgeCompleted;
    private double rewardIdlePerSecond;
    private double rewardSurvivedTop3;
    private double rewardKnockbackDealtEdge;
    private double rewardComboLanded;

    // ── Shaping ──
    private boolean shapingEnabled;
    private double shapingHealthWeight;
    private double shapingEquipmentWeight;
    private double shapingTimeWeight;

    /**
     * Creates and loads the learning configuration from learning_config.yml.
     *
     * @param plugin the plugin instance
     */
    public LearningConfig(@Nonnull SkyWarsTrainer plugin) {
        this.plugin = plugin;
        load();
    }

    /**
     * Loads or reloads all values from learning_config.yml.
     * Missing keys fall back to embedded defaults.
     */
    public void load() {
        // Save default resource if missing
        File file = new File(plugin.getDataFolder(), "learning_config.yml");
        if (!file.exists()) {
            plugin.saveResource("learning_config.yml", false);
        }

        FileConfiguration config = YamlConfiguration.loadConfiguration(file);

        // Merge defaults from JAR resource
        InputStream defaultStream = plugin.getResource("learning_config.yml");
        if (defaultStream != null) {
            YamlConfiguration defaults = YamlConfiguration.loadConfiguration(
                    new InputStreamReader(defaultStream, StandardCharsets.UTF_8));
            config.setDefaults(defaults);
        }

        // ── Learning section ──
        ConfigurationSection learning = config.getConfigurationSection("learning");
        this.enabled = getOrDefault(learning, "enabled", true);
        this.learningRate = getOrDefault(learning, "learning-rate", 0.1);
        this.discountFactor = getOrDefault(learning, "discount-factor", 0.9);
        this.weightSensitivity = getOrDefault(learning, "weight-sensitivity", 0.5);
        this.maxAdjustmentMultiplier = getOrDefault(learning, "max-adjustment-multiplier", 2.5);
        this.minAdjustmentMultiplier = getOrDefault(learning, "min-adjustment-multiplier", 0.3);
        this.lambda = getOrDefault(learning, "lambda", 0.7);
        this.tracePruneThreshold = getOrDefault(learning, "trace-prune-threshold", 0.001);
        this.maxGamesBeforeStable = getOrDefaultInt(learning, "max-games-before-stable", 200);
        this.stabilityLrMultiplier = getOrDefault(learning, "stability-lr-multiplier", 0.1);

        // ── Memory section ──
        ConfigurationSection memory = config.getConfigurationSection("memory");
        this.maxEntries = getOrDefaultInt(memory, "max-entries", 50000);
        this.binsPerDimension = getOrDefaultInt(memory, "bins-per-dimension", 3);
        this.recencyHalfLifeGames = getOrDefaultInt(memory, "recency-half-life-games", 75);
        this.minVisitsForConfidence = getOrDefaultInt(memory, "min-visits-for-confidence", 3);
        this.confidencePatienceGames = getOrDefaultInt(memory, "confidence-patience-games", 15);
        this.contradictionLookback = getOrDefaultInt(memory, "contradiction-lookback", 20);
        this.contradictionThreshold = getOrDefault(memory, "contradiction-threshold", 0.6);
        this.consolidationSimilarity = getOrDefault(memory, "consolidation-similarity", 0.95);

        // ── Replay section ──
        ConfigurationSection replay = config.getConfigurationSection("replay");
        this.replayEnabled = getOrDefault(replay, "enabled", true);
        this.bufferCapacity = getOrDefaultInt(replay, "buffer-capacity", 8000);
        this.miniBatchSize = getOrDefaultInt(replay, "mini-batch-size", 32);
        this.replayRoundsPerGame = getOrDefaultInt(replay, "replay-rounds-per-game", 5);
        this.priorityAlpha = getOrDefault(replay, "priority-alpha", 0.6);
        this.importanceSamplingBetaStart = getOrDefault(replay, "importance-sampling-beta-start", 0.4);
        this.importanceSamplingBetaEnd = getOrDefault(replay, "importance-sampling-beta-end", 1.0);
        this.betaAnnealGames = getOrDefaultInt(replay, "beta-anneal-games", 200);
        this.priorityEpsilon = getOrDefault(replay, "priority-epsilon", 0.01);
        this.maxExperienceAgeGames = getOrDefaultInt(replay, "max-experience-age-games", 300);

        // ── Rewards section ──
        ConfigurationSection rewards = config.getConfigurationSection("rewards");
        this.rewardKill = getOrDefault(rewards, "kill", 1.0);
        this.rewardWin = getOrDefault(rewards, "win", 3.0);
        this.rewardDeath = getOrDefault(rewards, "death", -2.0);
        this.rewardLose = getOrDefault(rewards, "lose", -1.0);
        this.rewardWeaponUpgrade = getOrDefault(rewards, "weapon-upgrade", 0.3);
        this.rewardArmorUpgrade = getOrDefault(rewards, "armor-upgrade", 0.2);
        this.rewardHealthGainedPerHeart = getOrDefault(rewards, "health-gained-per-heart", 0.05);
        this.rewardHealthLostPerHeart = getOrDefault(rewards, "health-lost-per-heart", -0.1);
        this.rewardChestLooted = getOrDefault(rewards, "chest-looted", 0.15);
        this.rewardEnemyFled = getOrDefault(rewards, "enemy-fled", 0.2);
        this.rewardForcedToFlee = getOrDefault(rewards, "forced-to-flee", -0.3);
        this.rewardBridgeCompleted = getOrDefault(rewards, "bridge-completed", 0.1);
        this.rewardIdlePerSecond = getOrDefault(rewards, "idle-per-second", -0.01);
        this.rewardSurvivedTop3 = getOrDefault(rewards, "survived-top-3", 0.5);
        this.rewardKnockbackDealtEdge = getOrDefault(rewards, "knockback-dealt-edge", 0.4);
        this.rewardComboLanded = getOrDefault(rewards, "combo-landed", 0.25);

        // ── Shaping section ──
        ConfigurationSection shaping = config.getConfigurationSection("shaping");
        this.shapingEnabled = getOrDefault(shaping, "enabled", true);
        this.shapingHealthWeight = getOrDefault(shaping, "health-weight", 0.5);
        this.shapingEquipmentWeight = getOrDefault(shaping, "equipment-weight", 0.3);
        this.shapingTimeWeight = getOrDefault(shaping, "time-weight", 0.2);
    }

    // ── Safe accessors that handle null sections ──

    private static double getOrDefault(ConfigurationSection section, String key, double def) {
        if (section == null) return def;
        return section.getDouble(key, def);
    }

    private static int getOrDefaultInt(ConfigurationSection section, String key, int def) {
        if (section == null) return def;
        return section.getInt(key, def);
    }

    private static boolean getOrDefault(ConfigurationSection section, String key, boolean def) {
        if (section == null) return def;
        return section.getBoolean(key, def);
    }

    // ═════════════════════════════════════════════════════════════
    //  GETTERS
    // ═════════════════════════════════════════════════════════════

    // ── Learning ──
    public boolean isEnabled() { return enabled; }
    public double getLearningRate() { return learningRate; }
    public double getDiscountFactor() { return discountFactor; }
    public double getWeightSensitivity() { return weightSensitivity; }
    public double getMaxAdjustmentMultiplier() { return maxAdjustmentMultiplier; }
    public double getMinAdjustmentMultiplier() { return minAdjustmentMultiplier; }
    public double getLambda() { return lambda; }
    public double getTracePruneThreshold() { return tracePruneThreshold; }
    public int getMaxGamesBeforeStable() { return maxGamesBeforeStable; }
    public double getStabilityLrMultiplier() { return stabilityLrMultiplier; }

    // ── Memory ──
    public int getMaxEntries() { return maxEntries; }
    public int getBinsPerDimension() { return binsPerDimension; }
    public int getRecencyHalfLifeGames() { return recencyHalfLifeGames; }
    public int getMinVisitsForConfidence() { return minVisitsForConfidence; }
    public int getConfidencePatienceGames() { return confidencePatienceGames; }
    public int getContradictionLookback() { return contradictionLookback; }
    public double getContradictionThreshold() { return contradictionThreshold; }
    public double getConsolidationSimilarity() { return consolidationSimilarity; }

    // ── Replay ──
    public boolean isReplayEnabled() { return replayEnabled; }
    public int getBufferCapacity() { return bufferCapacity; }
    public int getMiniBatchSize() { return miniBatchSize; }
    public int getReplayRoundsPerGame() { return replayRoundsPerGame; }
    public double getPriorityAlpha() { return priorityAlpha; }
    public double getImportanceSamplingBetaStart() { return importanceSamplingBetaStart; }
    public double getImportanceSamplingBetaEnd() { return importanceSamplingBetaEnd; }
    public int getBetaAnnealGames() { return betaAnnealGames; }
    public double getPriorityEpsilon() { return priorityEpsilon; }
    public int getMaxExperienceAgeGames() { return maxExperienceAgeGames; }

    // ── Rewards ──
    public double getRewardKill() { return rewardKill; }
    public double getRewardWin() { return rewardWin; }
    public double getRewardDeath() { return rewardDeath; }
    public double getRewardLose() { return rewardLose; }
    public double getRewardWeaponUpgrade() { return rewardWeaponUpgrade; }
    public double getRewardArmorUpgrade() { return rewardArmorUpgrade; }
    public double getRewardHealthGainedPerHeart() { return rewardHealthGainedPerHeart; }
    public double getRewardHealthLostPerHeart() { return rewardHealthLostPerHeart; }
    public double getRewardChestLooted() { return rewardChestLooted; }
    public double getRewardEnemyFled() { return rewardEnemyFled; }
    public double getRewardForcedToFlee() { return rewardForcedToFlee; }
    public double getRewardBridgeCompleted() { return rewardBridgeCompleted; }
    public double getRewardIdlePerSecond() { return rewardIdlePerSecond; }
    public double getRewardSurvivedTop3() { return rewardSurvivedTop3; }
    public double getRewardKnockbackDealtEdge() { return rewardKnockbackDealtEdge; }
    public double getRewardComboLanded() { return rewardComboLanded; }

    // ── Shaping ──
    public boolean isShapingEnabled() { return shapingEnabled; }
    public double getShapingHealthWeight() { return shapingHealthWeight; }
    public double getShapingEquipmentWeight() { return shapingEquipmentWeight; }
    public double getShapingTimeWeight() { return shapingTimeWeight; }
}
