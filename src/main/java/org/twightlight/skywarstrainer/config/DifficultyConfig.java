package org.twightlight.skywarstrainer.config;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.twightlight.skywarstrainer.SkyWarsTrainer;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.Collections;
import java.util.EnumMap;
import java.util.Map;
import java.util.logging.Level;

/**
 * Loads and stores difficulty profiles from {@code difficulty.yml}.
 *
 * <p>Each difficulty level (BEGINNER, EASY, MEDIUM, HARD, EXPERT) maps to a
 * {@link DifficultyProfile} containing all numeric parameters that every
 * subsystem reads. Parameters are never hardcoded — they are always read
 * from the profile, allowing smooth scaling.</p>
 *
 * <p>The difficulty system does NOT use if/else branches. Instead, every subsystem
 * reads the numeric parameters from the active profile and scales its behavior
 * accordingly. A bot with aimAccuracy=0.3 aims poorly; aimAccuracy=0.95 aims
 * precisely. The subsystem code is identical — only the numbers change.</p>
 */
public class DifficultyConfig {

    private final SkyWarsTrainer plugin;

    /** Loaded profiles keyed by difficulty enum. */
    private final Map<Difficulty, DifficultyProfile> profiles;

    /**
     * Creates a new DifficultyConfig for the given plugin.
     *
     * @param plugin the owning plugin instance
     */
    public DifficultyConfig(@Nonnull SkyWarsTrainer plugin) {
        this.plugin = plugin;
        this.profiles = new EnumMap<>(Difficulty.class);
    }

    /**
     * Loads all difficulty profiles from difficulty.yml.
     * Must be called after {@link ConfigManager#loadAll()}.
     */
    public void load() {
        profiles.clear();
        FileConfiguration config = plugin.getConfigManager().getDifficultyFileConfig();
        ConfigurationSection difficultiesSection = config.getConfigurationSection("difficulties");

        if (difficultiesSection == null) {
            plugin.getLogger().severe("No 'difficulties' section found in difficulty.yml! Using hardcoded defaults.");
            loadHardcodedDefaults();
            return;
        }

        for (Difficulty difficulty : Difficulty.values()) {
            ConfigurationSection section = difficultiesSection.getConfigurationSection(difficulty.name());
            if (section == null) {
                plugin.getLogger().warning("Missing difficulty section for " + difficulty.name()
                        + " in difficulty.yml — using defaults.");
                profiles.put(difficulty, createDefault(difficulty));
                continue;
            }

            try {
                DifficultyProfile profile = parseProfile(difficulty, section);
                profiles.put(difficulty, profile);
                if (plugin.getConfigManager().isDebugMode()) {
                    plugin.getLogger().info("[DEBUG] Loaded difficulty: " + difficulty.name()
                            + " (aimAccuracy=" + profile.getAimAccuracy()
                            + ", maxCPS=" + profile.getMaxCPS()
                            + ", bridgeSpeed=" + profile.getBridgeSpeed() + ")");
                }
            } catch (Exception e) {
                plugin.getLogger().log(Level.WARNING, "Error parsing difficulty " + difficulty.name()
                        + " — using defaults.", e);
                profiles.put(difficulty, createDefault(difficulty));
            }
        }

        plugin.getLogger().info("Loaded " + profiles.size() + " difficulty profiles.");
    }

    /**
     * Returns the number of loaded difficulty profiles.
     *
     * @return the count
     */
    public int getLoadedCount() {
        return profiles.size();
    }

    /**
     * Returns the profile for the given difficulty level.
     *
     * @param difficulty the difficulty
     * @return the profile (never null; falls back to MEDIUM defaults if missing)
     */
    @Nonnull
    public DifficultyProfile getProfile(@Nonnull Difficulty difficulty) {
        DifficultyProfile profile = profiles.get(difficulty);
        if (profile == null) {
            plugin.getLogger().warning("No profile for " + difficulty.name() + ", using MEDIUM defaults.");
            return createDefault(Difficulty.MEDIUM);
        }
        return profile;
    }

    /**
     * Returns the profile for a difficulty specified by name (case-insensitive).
     *
     * @param name the difficulty name
     * @return the profile, or null if the name is not a valid difficulty
     */
    @Nullable
    public DifficultyProfile getProfile(@Nonnull String name) {
        try {
            Difficulty difficulty = Difficulty.valueOf(name.toUpperCase());
            return getProfile(difficulty);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    /**
     * Returns an unmodifiable map of all loaded profiles.
     *
     * @return all profiles
     */
    @Nonnull
    public Map<Difficulty, DifficultyProfile> getAllProfiles() {
        return Collections.unmodifiableMap(profiles);
    }

    // ─── Parsing ────────────────────────────────────────────────

    /**
     * Parses a single difficulty profile from a YAML configuration section.
     *
     * @param difficulty the difficulty being parsed
     * @param section    the YAML section containing the parameters
     * @return the parsed DifficultyProfile
     */
    @Nonnull
    private DifficultyProfile parseProfile(@Nonnull Difficulty difficulty,
                                           @Nonnull ConfigurationSection section) {
        DifficultyProfile.Builder builder = new DifficultyProfile.Builder(difficulty);

        builder.reactionTimeMin(section.getInt("reactionTimeMin", 250));
        builder.reactionTimeMax(section.getInt("reactionTimeMax", 500));
        builder.aimAccuracy(section.getDouble("aimAccuracy", 0.7));
        builder.aimSpeedDegPerTick(section.getDouble("aimSpeedDegPerTick", 10.0));
        builder.maxCPS(section.getInt("maxCPS", 10));
        builder.cpsVariance(section.getDouble("cpsVariance", 1.5));
        builder.sprintResetChance(section.getDouble("sprintResetChance", 0.5));
        builder.wTapEfficiency(section.getDouble("wTapEfficiency", 0.4));
        builder.strafeIntensity(section.getDouble("strafeIntensity", 0.5));
        builder.strafeUnpredictability(section.getDouble("strafeUnpredictability", 0.4));
        builder.blockHitChance(section.getDouble("blockHitChance", 0.2));
        builder.projectileAccuracy(section.getDouble("projectileAccuracy", 0.5));
        builder.bridgeSpeed(section.getDouble("bridgeSpeed", 4.0));
        builder.bridgeMaxType(section.getString("bridgeMaxType", "NINJA"));
        builder.bridgeFailRate(section.getDouble("bridgeFailRate", 0.03));
        builder.lootSpeedMultiplier(section.getDouble("lootSpeedMultiplier", 1.0));
        builder.lootDecisionQuality(section.getDouble("lootDecisionQuality", 0.7));
        builder.inventoryManageSkill(section.getDouble("inventoryManageSkill", 0.6));
        builder.hotbarOrganization(section.getDouble("hotbarOrganization", 0.6));
        builder.decisionQuality(section.getDouble("decisionQuality", 0.7));
        builder.awarenessRadius(section.getInt("awarenessRadius", 40));
        builder.fleeHealthThreshold(section.getDouble("fleeHealthThreshold", 0.3));
        builder.pearlUsageIQ(section.getDouble("pearlUsageIQ", 0.4));
        builder.potionUsageIQ(section.getDouble("potionUsageIQ", 0.5));
        builder.comboLength(section.getInt("comboLength", 3));
        builder.rodUsageSkill(section.getDouble("rodUsageSkill", 0.3));
        builder.blockPlaceChance(section.getDouble("blockPlaceChance", 0.3));
        builder.comboBreakPriority(section.getDouble("comboBreakPriority", 0.5));
        builder.antiKBReduction(section.getDouble("antiKBReduction", 0.15));
        builder.waterBucketMLG(section.getDouble("waterBucketMLG", 0.2));
        builder.mistakeFrequency(section.getDouble("mistakeFrequency", 4.0));
        builder.headMovementNoise(section.getDouble("headMovementNoise", 0.12));
        builder.itemDropOnDeathPanic(section.getDouble("itemDropOnDeathPanic", 0.1));
        builder.jumpBridgeChance(section.getDouble("jumpBridgeChance", 0.2));
        builder.stairBridgeSkill(section.getDouble("stairBridgeSkill", 0.5));
        builder.fakeBridgeChance(section.getDouble("fakeBridgeChance", 0.1));
        builder.bridgeSafetyRailChance(section.getDouble("bridgeSafetyRailChance", 0.2));
        builder.diagonalApproachTendency(section.getDouble("diagonalApproachTendency", 0.3));
        builder.verticalApproachTendency(section.getDouble("verticalApproachTendency", 0.15));
        builder.splitPathChance(section.getDouble("splitPathChance", 0.05));
        builder.approachPatienceTicks(section.getInt("approachPatienceTicks", 80));
        builder.edgeKnockSkill(section.getDouble("edgeKnockSkill", 0.35));
        builder.comboLockSkill(section.getDouble("comboLockSkill", 0.4));
        builder.kbCancelSkill(section.getDouble("kbCancelSkill", 0.2));
        builder.preferredEngageDistance(section.getDouble("preferredEngageDistance", 3.0));
        builder.highGroundPriority(section.getDouble("highGroundPriority", 0.35));
        builder.islandRotationTendency(section.getDouble("islandRotationTendency", 0.2));
        builder.thirdPartyTendency(section.getDouble("thirdPartyTendency", 0.3));
        builder.bridgeCutSkill(section.getDouble("bridgeCutSkill", 0.3));
        builder.projectileZoningTendency(section.getDouble("projectileZoningTendency", 0.35));
        builder.retreatHealSkill(section.getDouble("retreatHealSkill", 0.4));
        builder.counterPlayIQ(section.getDouble("counterPlayIQ", 0.35));
        builder.antiRushReaction(section.getDouble("antiRushReaction", 0.4));
        builder.baitDetectionSkill(section.getDouble("baitDetectionSkill", 0.3));
        builder.learningRate(section.getDouble("learningRate", 0.1));

        // ── Strategy Planning ──
        builder.strategyPlanningEnabled(section.getBoolean("strategyPlanningEnabled", true));
        builder.planComplexity(section.getInt("planComplexity", 3));
        builder.planReevalIntervalTicks(section.getInt("planReevalIntervalTicks", 200));
        builder.planConfidenceDecayRate(section.getDouble("planConfidenceDecayRate", 0.003));
        builder.planLearningInfluence(section.getDouble("planLearningInfluence", 0.3));
        builder.planLLMConsultChance(section.getDouble("planLLMConsultChance", 0.2));
        builder.planFallbackEnabled(section.getBoolean("planFallbackEnabled", true));

        // ── Threat Prediction ──
        builder.threatPredictionEnabled(section.getBoolean("threatPredictionEnabled", true));
        builder.threatPredictionAccuracy(section.getDouble("threatPredictionAccuracy", 0.4));

        return builder.build();
    }

    /**
     * Creates a default profile for a difficulty if YAML parsing fails.
     * These match the values from the specification table.
     */
    @Nonnull
    private DifficultyProfile createDefault(@Nonnull Difficulty difficulty) {
        DifficultyProfile.Builder b = new DifficultyProfile.Builder(difficulty);
        switch (difficulty) {
            case BEGINNER:
                b.reactionTimeMin(600).reactionTimeMax(1200).aimAccuracy(0.3).aimSpeedDegPerTick(3.0)
                        .maxCPS(5).cpsVariance(3.0).sprintResetChance(0.0).wTapEfficiency(0.0)
                        .strafeIntensity(0.1).strafeUnpredictability(0.0).blockHitChance(0.0)
                        .projectileAccuracy(0.15).bridgeSpeed(1.5).bridgeMaxType("NORMAL").bridgeFailRate(0.15)
                        .lootSpeedMultiplier(0.5).lootDecisionQuality(0.3).inventoryManageSkill(0.2)
                        .hotbarOrganization(0.1).decisionQuality(0.3).awarenessRadius(15)
                        .fleeHealthThreshold(0.15).pearlUsageIQ(0.0).potionUsageIQ(0.0).comboLength(1)
                        .rodUsageSkill(0.0).blockPlaceChance(0.05).comboBreakPriority(0.1)
                        .antiKBReduction(0.0).waterBucketMLG(0.0).mistakeFrequency(12.0)
                        .headMovementNoise(0.3).itemDropOnDeathPanic(0.4);
                b.jumpBridgeChance(0.0).stairBridgeSkill(0.1).fakeBridgeChance(0.0)
                        .bridgeSafetyRailChance(0.0).diagonalApproachTendency(0.0)
                        .verticalApproachTendency(0.0).splitPathChance(0.0)
                        .approachPatienceTicks(20).edgeKnockSkill(0.05).comboLockSkill(0.1)
                        .kbCancelSkill(0.0).preferredEngageDistance(2.0)
                        .highGroundPriority(0.05).islandRotationTendency(0.0)
                        .thirdPartyTendency(0.0).bridgeCutSkill(0.0)
                        .projectileZoningTendency(0.05).retreatHealSkill(0.1)
                        .counterPlayIQ(0.05).antiRushReaction(0.1).baitDetectionSkill(0.0).learningRate(0.15);
                b.strategyPlanningEnabled(false).planComplexity(1).planReevalIntervalTicks(400)
                        .planConfidenceDecayRate(0.005).planLearningInfluence(0.0)
                        .planLLMConsultChance(0.0).planFallbackEnabled(false);
                b.threatPredictionEnabled(false).threatPredictionAccuracy(0.0);
                break;
            case EASY:
                b.reactionTimeMin(400).reactionTimeMax(800).aimAccuracy(0.5).aimSpeedDegPerTick(5.0)
                        .maxCPS(7).cpsVariance(2.0).sprintResetChance(0.15).wTapEfficiency(0.1)
                        .strafeIntensity(0.3).strafeUnpredictability(0.2).blockHitChance(0.05)
                        .projectileAccuracy(0.3).bridgeSpeed(2.5).bridgeMaxType("NORMAL").bridgeFailRate(0.08)
                        .lootSpeedMultiplier(0.7).lootDecisionQuality(0.5).inventoryManageSkill(0.4)
                        .hotbarOrganization(0.3).decisionQuality(0.5).awarenessRadius(25)
                        .fleeHealthThreshold(0.2).pearlUsageIQ(0.1).potionUsageIQ(0.2).comboLength(2)
                        .rodUsageSkill(0.1).blockPlaceChance(0.2).comboBreakPriority(0.3)
                        .antiKBReduction(0.0).waterBucketMLG(0.0).mistakeFrequency(8.0)
                        .headMovementNoise(0.2).itemDropOnDeathPanic(0.25);
                b.jumpBridgeChance(0.05).stairBridgeSkill(0.25).fakeBridgeChance(0.0)
                        .bridgeSafetyRailChance(0.1).diagonalApproachTendency(0.1)
                        .verticalApproachTendency(0.0).splitPathChance(0.0)
                        .approachPatienceTicks(40).edgeKnockSkill(0.15).comboLockSkill(0.2)
                        .kbCancelSkill(0.05).preferredEngageDistance(2.5)
                        .highGroundPriority(0.15).islandRotationTendency(0.05)
                        .thirdPartyTendency(0.1).bridgeCutSkill(0.1)
                        .projectileZoningTendency(0.15).retreatHealSkill(0.2)
                        .counterPlayIQ(0.15).antiRushReaction(0.2).baitDetectionSkill(0.1).learningRate(0.12);
                b.strategyPlanningEnabled(true).planComplexity(2).planReevalIntervalTicks(300)
                        .planConfidenceDecayRate(0.004).planLearningInfluence(0.1)
                        .planLLMConsultChance(0.0).planFallbackEnabled(false);
                b.threatPredictionEnabled(false).threatPredictionAccuracy(0.1);
                break;
            case MEDIUM:
                b.reactionTimeMin(250).reactionTimeMax(500).aimAccuracy(0.7).aimSpeedDegPerTick(10.0)
                        .maxCPS(10).cpsVariance(1.5).sprintResetChance(0.5).wTapEfficiency(0.4)
                        .strafeIntensity(0.5).strafeUnpredictability(0.4).blockHitChance(0.2)
                        .projectileAccuracy(0.5).bridgeSpeed(4.0).bridgeMaxType("NINJA").bridgeFailRate(0.03)
                        .lootSpeedMultiplier(1.0).lootDecisionQuality(0.7).inventoryManageSkill(0.6)
                        .hotbarOrganization(0.6).decisionQuality(0.7).awarenessRadius(40)
                        .fleeHealthThreshold(0.3).pearlUsageIQ(0.4).potionUsageIQ(0.5).comboLength(3)
                        .rodUsageSkill(0.3).blockPlaceChance(0.3).comboBreakPriority(0.5)
                        .antiKBReduction(0.15).waterBucketMLG(0.2).mistakeFrequency(4.0)
                        .headMovementNoise(0.12).itemDropOnDeathPanic(0.1);
                b.jumpBridgeChance(0.2).stairBridgeSkill(0.5).fakeBridgeChance(0.1)
                        .bridgeSafetyRailChance(0.2).diagonalApproachTendency(0.3)
                        .verticalApproachTendency(0.15).splitPathChance(0.05)
                        .approachPatienceTicks(80).edgeKnockSkill(0.35).comboLockSkill(0.4)
                        .kbCancelSkill(0.2).preferredEngageDistance(3.0)
                        .highGroundPriority(0.35).islandRotationTendency(0.2)
                        .thirdPartyTendency(0.3).bridgeCutSkill(0.3)
                        .projectileZoningTendency(0.35).retreatHealSkill(0.4)
                        .counterPlayIQ(0.35).antiRushReaction(0.4).baitDetectionSkill(0.3).learningRate(0.10);
                b.strategyPlanningEnabled(true).planComplexity(3).planReevalIntervalTicks(200)
                        .planConfidenceDecayRate(0.003).planLearningInfluence(0.3)
                        .planLLMConsultChance(0.2).planFallbackEnabled(true);
                b.threatPredictionEnabled(true).threatPredictionAccuracy(0.4);
                break;
            case HARD:
                b.reactionTimeMin(150).reactionTimeMax(300).aimAccuracy(0.85).aimSpeedDegPerTick(18.0)
                        .maxCPS(13).cpsVariance(1.0).sprintResetChance(0.8).wTapEfficiency(0.7)
                        .strafeIntensity(0.75).strafeUnpredictability(0.6).blockHitChance(0.45)
                        .projectileAccuracy(0.7).bridgeSpeed(6.0).bridgeMaxType("NINJA").bridgeFailRate(0.01)
                        .lootSpeedMultiplier(1.3).lootDecisionQuality(0.85).inventoryManageSkill(0.8)
                        .hotbarOrganization(0.85).decisionQuality(0.85).awarenessRadius(60)
                        .fleeHealthThreshold(0.35).pearlUsageIQ(0.7).potionUsageIQ(0.8).comboLength(5)
                        .rodUsageSkill(0.6).blockPlaceChance(0.45).comboBreakPriority(0.7)
                        .antiKBReduction(0.3).waterBucketMLG(0.6).mistakeFrequency(2.0)
                        .headMovementNoise(0.07).itemDropOnDeathPanic(0.02);
                b.jumpBridgeChance(0.5).stairBridgeSkill(0.75).fakeBridgeChance(0.3)
                        .bridgeSafetyRailChance(0.15).diagonalApproachTendency(0.6)
                        .verticalApproachTendency(0.4).splitPathChance(0.2)
                        .approachPatienceTicks(120).edgeKnockSkill(0.6).comboLockSkill(0.7)
                        .kbCancelSkill(0.45).preferredEngageDistance(3.0)
                        .highGroundPriority(0.6).islandRotationTendency(0.4)
                        .thirdPartyTendency(0.55).bridgeCutSkill(0.6)
                        .projectileZoningTendency(0.6).retreatHealSkill(0.7)
                        .counterPlayIQ(0.6).antiRushReaction(0.65).baitDetectionSkill(0.6).learningRate(0.08);
                b.strategyPlanningEnabled(true).planComplexity(4).planReevalIntervalTicks(150)
                        .planConfidenceDecayRate(0.002).planLearningInfluence(0.6)
                        .planLLMConsultChance(0.5).planFallbackEnabled(true);
                b.threatPredictionEnabled(true).threatPredictionAccuracy(0.7);
                break;
            case EXPERT:
                b.reactionTimeMin(80).reactionTimeMax(150).aimAccuracy(0.95).aimSpeedDegPerTick(25.0)
                        .maxCPS(16).cpsVariance(0.5).sprintResetChance(0.95).wTapEfficiency(0.92)
                        .strafeIntensity(0.9).strafeUnpredictability(0.85).blockHitChance(0.7)
                        .projectileAccuracy(0.88).bridgeSpeed(9.0).bridgeMaxType("GOD").bridgeFailRate(0.002)
                        .lootSpeedMultiplier(1.6).lootDecisionQuality(0.95).inventoryManageSkill(0.95)
                        .hotbarOrganization(0.98).decisionQuality(0.95).awarenessRadius(80)
                        .fleeHealthThreshold(0.4).pearlUsageIQ(0.9).potionUsageIQ(0.95).comboLength(8)
                        .rodUsageSkill(0.9).blockPlaceChance(0.7).comboBreakPriority(0.95)
                        .antiKBReduction(0.45).waterBucketMLG(0.9).mistakeFrequency(0.5)
                        .headMovementNoise(0.03).itemDropOnDeathPanic(0.0);
                b.jumpBridgeChance(0.8).stairBridgeSkill(0.95).fakeBridgeChance(0.6)
                        .bridgeSafetyRailChance(0.05).diagonalApproachTendency(0.85)
                        .verticalApproachTendency(0.7).splitPathChance(0.5)
                        .approachPatienceTicks(200).edgeKnockSkill(0.9).comboLockSkill(0.92)
                        .kbCancelSkill(0.75).preferredEngageDistance(3.0)
                        .highGroundPriority(0.85).islandRotationTendency(0.7)
                        .thirdPartyTendency(0.8).bridgeCutSkill(0.9)
                        .projectileZoningTendency(0.85).retreatHealSkill(0.9)
                        .counterPlayIQ(0.9).antiRushReaction(0.9).baitDetectionSkill(0.85).learningRate(0.05);
                b.strategyPlanningEnabled(true).planComplexity(5).planReevalIntervalTicks(100)
                        .planConfidenceDecayRate(0.001).planLearningInfluence(0.9)
                        .planLLMConsultChance(0.8).planFallbackEnabled(true);
                b.threatPredictionEnabled(true).threatPredictionAccuracy(0.9);
                break;
            case NIGHTMARE:
                b.reactionTimeMin(30).reactionTimeMax(60)
                        .aimAccuracy(1.0)
                        .aimSpeedDegPerTick(45.0)
                        .maxCPS(20).cpsVariance(0.0)
                        .sprintResetChance(1.0).wTapEfficiency(1.0)
                        .strafeIntensity(1.0).strafeUnpredictability(1.0)
                        .blockHitChance(1.0)
                        .projectileAccuracy(1.0)
                        .bridgeSpeed(12.0).bridgeMaxType("GOD").bridgeFailRate(0.0)
                        .lootSpeedMultiplier(2.0).lootDecisionQuality(1.0)
                        .inventoryManageSkill(1.0).hotbarOrganization(1.0)
                        .decisionQuality(1.0).awarenessRadius(120)
                        .fleeHealthThreshold(0.5)
                        .pearlUsageIQ(1.0).potionUsageIQ(1.0)
                        .comboLength(15)
                        .rodUsageSkill(1.0).blockPlaceChance(1.0)
                        .comboBreakPriority(1.0).antiKBReduction(0.8)
                        .waterBucketMLG(1.0)
                        .mistakeFrequency(0.0)
                        .headMovementNoise(0.0)
                        .itemDropOnDeathPanic(0.0);
                b.jumpBridgeChance(1.0).stairBridgeSkill(1.0)
                        .fakeBridgeChance(0.8).bridgeSafetyRailChance(0.0)
                        .diagonalApproachTendency(1.0).verticalApproachTendency(1.0)
                        .splitPathChance(0.7).approachPatienceTicks(400)
                        .edgeKnockSkill(1.0).comboLockSkill(1.0)
                        .kbCancelSkill(1.0).preferredEngageDistance(3.0)
                        .highGroundPriority(1.0).islandRotationTendency(1.0)
                        .thirdPartyTendency(1.0).bridgeCutSkill(1.0)
                        .projectileZoningTendency(1.0).retreatHealSkill(1.0)
                        .counterPlayIQ(1.0).antiRushReaction(1.0).baitDetectionSkill(1.0)
                        .learningRate(0.03);
                b.strategyPlanningEnabled(true).planComplexity(6)
                        .planReevalIntervalTicks(50)
                        .planConfidenceDecayRate(0.0005)
                        .planLearningInfluence(1.0)
                        .planLLMConsultChance(1.0)
                        .planFallbackEnabled(true);
                b.threatPredictionEnabled(true).threatPredictionAccuracy(1.0);
                break;

        }
        return b.build();
    }

    /**
     * Loads hardcoded defaults for all difficulties when difficulty.yml is entirely missing.
     */
    private void loadHardcodedDefaults() {
        for (Difficulty difficulty : Difficulty.values()) {
            profiles.put(difficulty, createDefault(difficulty));
        }
    }

    // ═════════════════════════════════════════════════════════════
    //  INNER: Difficulty Enum
    // ═════════════════════════════════════════════════════════════

    /**
     * The five difficulty levels. Each defines a skill tier that maps to
     * numeric parameters in {@link DifficultyProfile}. Subsystems read
     * these parameters to scale behavior smoothly without if/else branches.
     */
    public enum Difficulty {
        BEGINNER,
        EASY,
        MEDIUM,
        HARD,
        EXPERT,
        NIGHTMARE;

        @Nullable
        public static Difficulty fromString(@Nullable String name) {
            if (name == null) return null;
            try {
                return valueOf(name.toUpperCase());
            } catch (IllegalArgumentException e) {
                return null;
            }
        }

        /**
         * Returns the ordinal as a 0.0–1.0+ fraction. BEGINNER=0.0, EXPERT=1.0,
         * NIGHTMARE=1.25 (above 1.0 so interpolation-based code scales beyond EXPERT).
         *
         * @return the difficulty fraction
         */
        public double asFraction() {
            if (this == NIGHTMARE) return 1.25;
            return ordinal() / 4.0;
        }
    }


    // ═════════════════════════════════════════════════════════════
    //  INNER: DifficultyProfile
    // ═════════════════════════════════════════════════════════════

    /**
     * An immutable data object holding all numeric parameters for a single
     * difficulty level. Every subsystem reads values from this profile to
     * determine bot behavior.
     *
     * <p>Constructed via the {@link Builder} pattern for readability and safety.</p>
     */
    public static final class DifficultyProfile {

        private final Difficulty difficulty;

        // ── Reaction & Timing ──
        private final int reactionTimeMin;
        private final int reactionTimeMax;

        // ── Aim ──
        private final double aimAccuracy;
        private final double aimSpeedDegPerTick;

        // ── Click / CPS ──
        private final int maxCPS;
        private final double cpsVariance;

        // ── Combat Mechanics ──
        private final double sprintResetChance;
        private final double wTapEfficiency;
        private final double strafeIntensity;
        private final double strafeUnpredictability;
        private final double blockHitChance;
        private final double projectileAccuracy;
        private final int comboLength;
        private final double rodUsageSkill;
        private final double blockPlaceChance;
        private final double comboBreakPriority;
        private final double antiKBReduction;

        // ── Bridging ──
        private final double bridgeSpeed;
        private final String bridgeMaxType;
        private final double bridgeFailRate;

        // ── Looting ──
        private final double lootSpeedMultiplier;
        private final double lootDecisionQuality;

        // ── Inventory ──
        private final double inventoryManageSkill;
        private final double hotbarOrganization;

        // ── Decision / Awareness ──
        private final double decisionQuality;
        private final int awarenessRadius;
        private final double fleeHealthThreshold;

        // ── Special Items ──
        private final double pearlUsageIQ;
        private final double potionUsageIQ;
        private final double waterBucketMLG;

        // ── Humanization ──
        private final double mistakeFrequency;
        private final double headMovementNoise;
        private final double itemDropOnDeathPanic;

        // ── Advanced Bridging Movement ──
        private final double jumpBridgeChance;
        private final double stairBridgeSkill;
        private final double fakeBridgeChance;
        private final double bridgeSafetyRailChance;

        // ── Approaching Strategy ──
        private final double diagonalApproachTendency;
        private final double verticalApproachTendency;
        private final double splitPathChance;
        private final int approachPatienceTicks;

        // ── Advanced Engagement ──
        private final double edgeKnockSkill;
        private final double comboLockSkill;
        private final double kbCancelSkill;
        private final double preferredEngageDistance;

        // ── Positional Strategy ──
        private final double highGroundPriority;
        private final double islandRotationTendency;
        private final double thirdPartyTendency;

        // ── Defensive Behavior ──
        private final double bridgeCutSkill;
        private final double projectileZoningTendency;
        private final double retreatHealSkill;

        // ── Counter-Play ──
        private final double counterPlayIQ;
        private final double antiRushReaction;
        private final double baitDetectionSkill;

        private final double learningRate;

        // ── Strategy Planning (NEW) ──
        private final boolean strategyPlanningEnabled;
        private final int planComplexity;
        private final int planReevalIntervalTicks;
        private final double planConfidenceDecayRate;
        private final double planLearningInfluence;
        private final double planLLMConsultChance;
        private final boolean planFallbackEnabled;

        // ── Threat Prediction (NEW) ──
        private final boolean threatPredictionEnabled;
        private final double threatPredictionAccuracy;

        private DifficultyProfile(Builder builder) {
            this.difficulty = builder.difficulty;
            this.reactionTimeMin = builder.reactionTimeMin;
            this.reactionTimeMax = builder.reactionTimeMax;
            this.aimAccuracy = builder.aimAccuracy;
            this.aimSpeedDegPerTick = builder.aimSpeedDegPerTick;
            this.maxCPS = builder.maxCPS;
            this.cpsVariance = builder.cpsVariance;
            this.sprintResetChance = builder.sprintResetChance;
            this.wTapEfficiency = builder.wTapEfficiency;
            this.strafeIntensity = builder.strafeIntensity;
            this.strafeUnpredictability = builder.strafeUnpredictability;
            this.blockHitChance = builder.blockHitChance;
            this.projectileAccuracy = builder.projectileAccuracy;
            this.bridgeSpeed = builder.bridgeSpeed;
            this.bridgeMaxType = builder.bridgeMaxType;
            this.bridgeFailRate = builder.bridgeFailRate;
            this.lootSpeedMultiplier = builder.lootSpeedMultiplier;
            this.lootDecisionQuality = builder.lootDecisionQuality;
            this.inventoryManageSkill = builder.inventoryManageSkill;
            this.hotbarOrganization = builder.hotbarOrganization;
            this.decisionQuality = builder.decisionQuality;
            this.awarenessRadius = builder.awarenessRadius;
            this.fleeHealthThreshold = builder.fleeHealthThreshold;
            this.pearlUsageIQ = builder.pearlUsageIQ;
            this.potionUsageIQ = builder.potionUsageIQ;
            this.comboLength = builder.comboLength;
            this.rodUsageSkill = builder.rodUsageSkill;
            this.blockPlaceChance = builder.blockPlaceChance;
            this.comboBreakPriority = builder.comboBreakPriority;
            this.antiKBReduction = builder.antiKBReduction;
            this.waterBucketMLG = builder.waterBucketMLG;
            this.mistakeFrequency = builder.mistakeFrequency;
            this.headMovementNoise = builder.headMovementNoise;
            this.itemDropOnDeathPanic = builder.itemDropOnDeathPanic;
            this.jumpBridgeChance = builder.jumpBridgeChance;
            this.stairBridgeSkill = builder.stairBridgeSkill;
            this.fakeBridgeChance = builder.fakeBridgeChance;
            this.bridgeSafetyRailChance = builder.bridgeSafetyRailChance;
            this.diagonalApproachTendency = builder.diagonalApproachTendency;
            this.verticalApproachTendency = builder.verticalApproachTendency;
            this.splitPathChance = builder.splitPathChance;
            this.approachPatienceTicks = builder.approachPatienceTicks;
            this.edgeKnockSkill = builder.edgeKnockSkill;
            this.comboLockSkill = builder.comboLockSkill;
            this.kbCancelSkill = builder.kbCancelSkill;
            this.preferredEngageDistance = builder.preferredEngageDistance;
            this.highGroundPriority = builder.highGroundPriority;
            this.islandRotationTendency = builder.islandRotationTendency;
            this.thirdPartyTendency = builder.thirdPartyTendency;
            this.bridgeCutSkill = builder.bridgeCutSkill;
            this.projectileZoningTendency = builder.projectileZoningTendency;
            this.retreatHealSkill = builder.retreatHealSkill;
            this.counterPlayIQ = builder.counterPlayIQ;
            this.antiRushReaction = builder.antiRushReaction;
            this.baitDetectionSkill = builder.baitDetectionSkill;
            this.learningRate = builder.learningRate;
            this.strategyPlanningEnabled = builder.strategyPlanningEnabled;
            this.planComplexity = builder.planComplexity;
            this.planReevalIntervalTicks = builder.planReevalIntervalTicks;
            this.planConfidenceDecayRate = builder.planConfidenceDecayRate;
            this.planLearningInfluence = builder.planLearningInfluence;
            this.planLLMConsultChance = builder.planLLMConsultChance;
            this.planFallbackEnabled = builder.planFallbackEnabled;
            this.threatPredictionEnabled = builder.threatPredictionEnabled;
            this.threatPredictionAccuracy = builder.threatPredictionAccuracy;
        }

        // ── Getters ─────────────────────────────────────────────

        /** @return the difficulty level this profile belongs to */
        @Nonnull
        public Difficulty getDifficulty() { return difficulty; }

        /** @return minimum reaction time in milliseconds */
        public int getReactionTimeMin() { return reactionTimeMin; }

        /** @return maximum reaction time in milliseconds */
        public int getReactionTimeMax() { return reactionTimeMax; }

        /** @return aim accuracy from 0.0 (terrible) to 1.0 (perfect) */
        public double getAimAccuracy() { return aimAccuracy; }

        /** @return maximum degrees per tick the bot can rotate toward a target */
        public double getAimSpeedDegPerTick() { return aimSpeedDegPerTick; }

        /** @return maximum clicks per second for melee attacks */
        public int getMaxCPS() { return maxCPS; }

        /** @return CPS variance (actual CPS = random in [maxCPS - variance, maxCPS]) */
        public double getCpsVariance() { return cpsVariance; }

        /** @return probability of performing a sprint-reset on each hit */
        public double getSprintResetChance() { return sprintResetChance; }

        /** @return W-tap timing efficiency from 0.0 to 1.0 */
        public double getWTapEfficiency() { return wTapEfficiency; }

        /** @return strafe movement intensity from 0.0 to 1.0 */
        public double getStrafeIntensity() { return strafeIntensity; }

        /** @return strafe pattern unpredictability from 0.0 to 1.0 */
        public double getStrafeUnpredictability() { return strafeUnpredictability; }

        /** @return probability of block-hitting between swings */
        public double getBlockHitChance() { return blockHitChance; }

        /** @return projectile aim accuracy from 0.0 to 1.0 */
        public double getProjectileAccuracy() { return projectileAccuracy; }

        /** @return blocks placed per second while bridging */
        public double getBridgeSpeed() { return bridgeSpeed; }

        /** @return the highest bridge technique name the bot can use */
        @Nonnull
        public String getBridgeMaxType() { return bridgeMaxType; }

        /** @return probability of bridge placement failure per block */
        public double getBridgeFailRate() { return bridgeFailRate; }

        /** @return multiplier on looting speed (1.0 = baseline) */
        public double getLootSpeedMultiplier() { return lootSpeedMultiplier; }

        /** @return quality of looting decisions from 0.0 to 1.0 */
        public double getLootDecisionQuality() { return lootDecisionQuality; }

        /** @return inventory management skill from 0.0 to 1.0 */
        public double getInventoryManageSkill() { return inventoryManageSkill; }

        /** @return hotbar organization quality from 0.0 to 1.0 */
        public double getHotbarOrganization() { return hotbarOrganization; }

        /** @return overall decision quality from 0.0 to 1.0 (controls utility AI noise) */
        public double getDecisionQuality() { return decisionQuality; }

        /** @return radius in blocks for enemy/chest/terrain detection */
        public int getAwarenessRadius() { return awarenessRadius; }

        /** @return HP fraction below which the bot considers fleeing */
        public double getFleeHealthThreshold() { return fleeHealthThreshold; }

        /** @return ender pearl usage intelligence from 0.0 to 1.0 */
        public double getPearlUsageIQ() { return pearlUsageIQ; }

        /** @return potion usage intelligence from 0.0 to 1.0 */
        public double getPotionUsageIQ() { return potionUsageIQ; }

        /** @return average maximum combo length the bot can sustain */
        public int getComboLength() { return comboLength; }

        /** @return fishing rod usage skill from 0.0 to 1.0 */
        public double getRodUsageSkill() { return rodUsageSkill; }

        /** @return chance to place blocks tactically during combat */
        public double getBlockPlaceChance() { return blockPlaceChance; }

        /** @return priority for placing blocks to break enemy combos */
        public double getComboBreakPriority() { return comboBreakPriority; }

        /** @return fraction of KB reduced via movement countering */
        public double getAntiKBReduction() { return antiKBReduction; }

        /** @return success chance for water bucket MLG */
        public double getWaterBucketMLG() { return waterBucketMLG; }

        /** @return intentional mistakes per minute */
        public double getMistakeFrequency() { return mistakeFrequency; }

        /** @return head movement noise magnitude in degrees */
        public double getHeadMovementNoise() { return headMovementNoise; }

        /** @return chance to panic-drop items when near death */
        public double getItemDropOnDeathPanic() { return itemDropOnDeathPanic; }

        /** @return chance to attempt jump bridging [0.0, 1.0] */
        public double getJumpBridgeChance() { return jumpBridgeChance; }

        /** @return stair bridge execution skill [0.0, 1.0] */
        public double getStairBridgeSkill() { return stairBridgeSkill; }

        /** @return chance to use fake/bait bridge tactics [0.0, 1.0] */
        public double getFakeBridgeChance() { return fakeBridgeChance; }

        /** @return chance to place safety rail blocks while bridging [0.0, 1.0] */
        public double getBridgeSafetyRailChance() { return bridgeSafetyRailChance; }

        /** @return tendency to use diagonal approach routes [0.0, 1.0] */
        public double getDiagonalApproachTendency() { return diagonalApproachTendency; }

        /** @return tendency to use vertical approach (tower + bridge) [0.0, 1.0] */
        public double getVerticalApproachTendency() { return verticalApproachTendency; }

        /** @return chance to use split-path approach [0.0, 1.0] */
        public double getSplitPathChance() { return splitPathChance; }

        /** @return patience ticks when waiting in approach strategies */
        public int getApproachPatienceTicks() { return approachPatienceTicks; }

        /** @return skill at positioning for edge/void knockback kills [0.0, 1.0] */
        public double getEdgeKnockSkill() { return edgeKnockSkill; }

        /** @return skill at maintaining combo lock distance [0.0, 1.0] */
        public double getComboLockSkill() { return comboLockSkill; }

        /** @return skill at cancelling incoming knockback [0.0, 1.0] */
        public double getKbCancelSkill() { return kbCancelSkill; }

        /** @return preferred engagement distance in blocks */
        public double getPreferredEngageDistance() { return preferredEngageDistance; }

        /** @return priority for gaining height advantage [0.0, 1.0] */
        public double getHighGroundPriority() { return highGroundPriority; }

        /** @return tendency to rotate between islands for loot [0.0, 1.0] */
        public double getIslandRotationTendency() { return islandRotationTendency; }

        /** @return tendency to third-party fights [0.0, 1.0] */
        public double getThirdPartyTendency() { return thirdPartyTendency; }

        /** @return skill at cutting enemy bridges [0.0, 1.0] */
        public double getBridgeCutSkill() { return bridgeCutSkill; }

        /** @return tendency to use projectiles for area denial [0.0, 1.0] */
        public double getProjectileZoningTendency() { return projectileZoningTendency; }

        /** @return skill at retreating, healing, and re-engaging [0.0, 1.0] */
        public double getRetreatHealSkill() { return retreatHealSkill; }

        /** @return intelligence at recognizing and countering enemy playstyles [0.0, 1.0] */
        public double getCounterPlayIQ() { return counterPlayIQ; }

        /** @return reaction skill against rush strategies [0.0, 1.0] */
        public double getAntiRushReaction() { return antiRushReaction; }

        /** @return skill at detecting bait/trick plays [0.0, 1.0] */
        public double getBaitDetectionSkill() { return baitDetectionSkill; }

        /** @return base learning rate for RL updates */
        public double getLearningRate() { return learningRate; }

        /** @return whether strategy planning is enabled for this difficulty */
        public boolean isStrategyPlanningEnabled() { return strategyPlanningEnabled; }

        /** @return maximum number of phases in a generated plan (1-5) */
        public int getPlanComplexity() { return planComplexity; }

        /** @return ticks between plan re-evaluations */
        public int getPlanReevalIntervalTicks() { return planReevalIntervalTicks; }

        /** @return per-tick confidence decay rate for active plans */
        public double getPlanConfidenceDecayRate() { return planConfidenceDecayRate; }

        /** @return how much Q-values from learning influence plan generation [0,1] */
        public double getPlanLearningInfluence() { return planLearningInfluence; }

        /** @return chance to consult LLM when generating a plan [0,1] */
        public double getPlanLLMConsultChance() { return planLLMConsultChance; }

        /** @return whether plans include fallback/abort conditions */
        public boolean isPlanFallbackEnabled() { return planFallbackEnabled; }

        /** @return whether threat prediction is enabled for this difficulty */
        public boolean isThreatPredictionEnabled() { return threatPredictionEnabled; }

        /** @return accuracy of enemy behavior predictions [0,1] */
        public double getThreatPredictionAccuracy() { return threatPredictionAccuracy; }

        /**
         * Returns a randomized reaction time within the profile's range.
         *
         * @return a reaction time in milliseconds
         */
        public int getRandomReactionTimeMs() {
            return org.twightlight.skywarstrainer.util.RandomUtil.nextInt(reactionTimeMin, reactionTimeMax);
        }

        /**
         * Calculates the ticks between mistakes based on mistakeFrequency.
         * {@code mistakeFrequency} is mistakes per minute; this converts to ticks.
         *
         * @return approximate ticks between mistakes (minimum 20)
         */
        public int getMistakeIntervalTicks() {
            if (mistakeFrequency <= 0.0) return Integer.MAX_VALUE;
            double secondsPerMistake = 60.0 / mistakeFrequency;
            return Math.max(20, (int) (secondsPerMistake * 20.0));
        }

        @Override
        public String toString() {
            return "DifficultyProfile{" + difficulty.name()
                    + ", aim=" + aimAccuracy
                    + ", cps=" + maxCPS
                    + ", bridge=" + bridgeSpeed
                    + ", decision=" + decisionQuality + "}";
        }

        // ── Builder ─────────────────────────────────────────────

        /**
         * Builder for constructing {@link DifficultyProfile} instances.
         * All fields have sensible defaults matching MEDIUM difficulty.
         */
        public static final class Builder {
            private final Difficulty difficulty;
            private int reactionTimeMin = 250;
            private int reactionTimeMax = 500;
            private double aimAccuracy = 0.7;
            private double aimSpeedDegPerTick = 10.0;
            private int maxCPS = 10;
            private double cpsVariance = 1.5;
            private double sprintResetChance = 0.5;
            private double wTapEfficiency = 0.4;
            private double strafeIntensity = 0.5;
            private double strafeUnpredictability = 0.4;
            private double blockHitChance = 0.2;
            private double projectileAccuracy = 0.5;
            private double bridgeSpeed = 4.0;
            private String bridgeMaxType = "NINJA";
            private double bridgeFailRate = 0.03;
            private double lootSpeedMultiplier = 1.0;
            private double lootDecisionQuality = 0.7;
            private double inventoryManageSkill = 0.6;
            private double hotbarOrganization = 0.6;
            private double decisionQuality = 0.7;
            private int awarenessRadius = 40;
            private double fleeHealthThreshold = 0.3;
            private double pearlUsageIQ = 0.4;
            private double potionUsageIQ = 0.5;
            private int comboLength = 3;
            private double rodUsageSkill = 0.3;
            private double blockPlaceChance = 0.3;
            private double comboBreakPriority = 0.5;
            private double antiKBReduction = 0.15;
            private double waterBucketMLG = 0.2;
            private double mistakeFrequency = 4.0;
            private double headMovementNoise = 0.12;
            private double itemDropOnDeathPanic = 0.1;
            private double jumpBridgeChance = 0.2;
            private double stairBridgeSkill = 0.5;
            private double fakeBridgeChance = 0.1;
            private double bridgeSafetyRailChance = 0.2;
            private double diagonalApproachTendency = 0.3;
            private double verticalApproachTendency = 0.15;
            private double splitPathChance = 0.05;
            private int approachPatienceTicks = 80;
            private double edgeKnockSkill = 0.35;
            private double comboLockSkill = 0.4;
            private double kbCancelSkill = 0.2;
            private double preferredEngageDistance = 3.0;
            private double highGroundPriority = 0.35;
            private double islandRotationTendency = 0.2;
            private double thirdPartyTendency = 0.3;
            private double bridgeCutSkill = 0.3;
            private double projectileZoningTendency = 0.35;
            private double retreatHealSkill = 0.4;
            private double counterPlayIQ = 0.35;
            private double antiRushReaction = 0.4;
            private double baitDetectionSkill = 0.3;
            private double learningRate = 0.1;
            private boolean strategyPlanningEnabled = true;
            private int planComplexity = 3;
            private int planReevalIntervalTicks = 200;
            private double planConfidenceDecayRate = 0.003;
            private double planLearningInfluence = 0.3;
            private double planLLMConsultChance = 0.2;
            private boolean planFallbackEnabled = true;
            private boolean threatPredictionEnabled = true;
            private double threatPredictionAccuracy = 0.4;

            public Builder(@Nonnull Difficulty difficulty) {
                this.difficulty = difficulty;
            }

            public Builder reactionTimeMin(int v) { this.reactionTimeMin = v; return this; }
            public Builder reactionTimeMax(int v) { this.reactionTimeMax = v; return this; }
            public Builder aimAccuracy(double v) { this.aimAccuracy = v; return this; }
            public Builder aimSpeedDegPerTick(double v) { this.aimSpeedDegPerTick = v; return this; }
            public Builder maxCPS(int v) { this.maxCPS = v; return this; }
            public Builder cpsVariance(double v) { this.cpsVariance = v; return this; }
            public Builder sprintResetChance(double v) { this.sprintResetChance = v; return this; }
            public Builder wTapEfficiency(double v) { this.wTapEfficiency = v; return this; }
            public Builder strafeIntensity(double v) { this.strafeIntensity = v; return this; }
            public Builder strafeUnpredictability(double v) { this.strafeUnpredictability = v; return this; }
            public Builder blockHitChance(double v) { this.blockHitChance = v; return this; }
            public Builder projectileAccuracy(double v) { this.projectileAccuracy = v; return this; }
            public Builder bridgeSpeed(double v) { this.bridgeSpeed = v; return this; }
            public Builder bridgeMaxType(String v) { this.bridgeMaxType = v; return this; }
            public Builder bridgeFailRate(double v) { this.bridgeFailRate = v; return this; }
            public Builder lootSpeedMultiplier(double v) { this.lootSpeedMultiplier = v; return this; }
            public Builder lootDecisionQuality(double v) { this.lootDecisionQuality = v; return this; }
            public Builder inventoryManageSkill(double v) { this.inventoryManageSkill = v; return this; }
            public Builder hotbarOrganization(double v) { this.hotbarOrganization = v; return this; }
            public Builder decisionQuality(double v) { this.decisionQuality = v; return this; }
            public Builder awarenessRadius(int v) { this.awarenessRadius = v; return this; }
            public Builder fleeHealthThreshold(double v) { this.fleeHealthThreshold = v; return this; }
            public Builder pearlUsageIQ(double v) { this.pearlUsageIQ = v; return this; }
            public Builder potionUsageIQ(double v) { this.potionUsageIQ = v; return this; }
            public Builder comboLength(int v) { this.comboLength = v; return this; }
            public Builder rodUsageSkill(double v) { this.rodUsageSkill = v; return this; }
            public Builder blockPlaceChance(double v) { this.blockPlaceChance = v; return this; }
            public Builder comboBreakPriority(double v) { this.comboBreakPriority = v; return this; }
            public Builder antiKBReduction(double v) { this.antiKBReduction = v; return this; }
            public Builder waterBucketMLG(double v) { this.waterBucketMLG = v; return this; }
            public Builder mistakeFrequency(double v) { this.mistakeFrequency = v; return this; }
            public Builder headMovementNoise(double v) { this.headMovementNoise = v; return this; }
            public Builder itemDropOnDeathPanic(double v) { this.itemDropOnDeathPanic = v; return this; }
            public Builder jumpBridgeChance(double v) { this.jumpBridgeChance = v; return this; }
            public Builder stairBridgeSkill(double v) { this.stairBridgeSkill = v; return this; }
            public Builder fakeBridgeChance(double v) { this.fakeBridgeChance = v; return this; }
            public Builder bridgeSafetyRailChance(double v) { this.bridgeSafetyRailChance = v; return this; }
            public Builder diagonalApproachTendency(double v) { this.diagonalApproachTendency = v; return this; }
            public Builder verticalApproachTendency(double v) { this.verticalApproachTendency = v; return this; }
            public Builder splitPathChance(double v) { this.splitPathChance = v; return this; }
            public Builder approachPatienceTicks(int v) { this.approachPatienceTicks = v; return this; }
            public Builder edgeKnockSkill(double v) { this.edgeKnockSkill = v; return this; }
            public Builder comboLockSkill(double v) { this.comboLockSkill = v; return this; }
            public Builder kbCancelSkill(double v) { this.kbCancelSkill = v; return this; }
            public Builder preferredEngageDistance(double v) { this.preferredEngageDistance = v; return this; }
            public Builder highGroundPriority(double v) { this.highGroundPriority = v; return this; }
            public Builder islandRotationTendency(double v) { this.islandRotationTendency = v; return this; }
            public Builder thirdPartyTendency(double v) { this.thirdPartyTendency = v; return this; }
            public Builder bridgeCutSkill(double v) { this.bridgeCutSkill = v; return this; }
            public Builder projectileZoningTendency(double v) { this.projectileZoningTendency = v; return this; }
            public Builder retreatHealSkill(double v) { this.retreatHealSkill = v; return this; }
            public Builder counterPlayIQ(double v) { this.counterPlayIQ = v; return this; }
            public Builder antiRushReaction(double v) { this.antiRushReaction = v; return this; }
            public Builder baitDetectionSkill(double v) { this.baitDetectionSkill = v; return this; }
            public Builder learningRate(double v) { this.learningRate = v; return this; }
            public Builder strategyPlanningEnabled(boolean v) { this.strategyPlanningEnabled = v; return this; }
            public Builder planComplexity(int v) { this.planComplexity = v; return this; }
            public Builder planReevalIntervalTicks(int v) { this.planReevalIntervalTicks = v; return this; }
            public Builder planConfidenceDecayRate(double v) { this.planConfidenceDecayRate = v; return this; }
            public Builder planLearningInfluence(double v) { this.planLearningInfluence = v; return this; }
            public Builder planLLMConsultChance(double v) { this.planLLMConsultChance = v; return this; }
            public Builder planFallbackEnabled(boolean v) { this.planFallbackEnabled = v; return this; }
            public Builder threatPredictionEnabled(boolean v) { this.threatPredictionEnabled = v; return this; }
            public Builder threatPredictionAccuracy(double v) { this.threatPredictionAccuracy = v; return this; }

            @Nonnull
            public DifficultyProfile build() {
                return new DifficultyProfile(this);
            }
        }
    }
}
