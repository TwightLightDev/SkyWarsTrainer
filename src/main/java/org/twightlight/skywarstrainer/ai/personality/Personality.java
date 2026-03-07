package org.twightlight.skywarstrainer.ai.personality;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.EnumMap;
import java.util.Map;

/**
 * Enumeration of all 12 bot personality types. Each personality modifies
 * the bot's Utility AI weights, unlocks/disables certain behaviors, and
 * tweaks numeric parameters.
 *
 * <p>A bot may have 1–3 personalities. Conflicting personalities cannot coexist
 * (see {@link PersonalityConflictTable}). Personalities are NOT difficulty levels —
 * a BEGINNER bot can be AGGRESSIVE (it charges in recklessly and dies, but it tries).</p>
 *
 * <p>Each personality carries its own set of weight modifiers for utility actions
 * and numeric parameter multipliers that stack multiplicatively when multiple
 * personalities are assigned to a single bot.</p>
 */
public enum Personality {


    AGGRESSIVE("Aggressive",
            "This bot wants blood. Rushes enemies, fights under-geared, chases relentlessly.",
            buildModifiers(
                    "FIGHT", 1.8, "HUNT", 2.0, "LOOT", 0.5, "FLEE", 0.4,
                    "fleeHealthThreshold", 0.5, "chaseDistance", 2.0,
                    "meleePreference", 1.5, "interruptLootRange", 30.0,
                    // Phase 7 additions:
                    "diagonalApproachTendency", 0.5
            )),

    PASSIVE("Passive",
            "Avoids fights. Loots fully, enchants, fights only when cornered or in endgame.",
            buildModifiers(
                    "FIGHT", 0.4, "FLEE", 2.0, "LOOT", 1.5, "ENCHANT", 1.8,
                    "fleeHealthThreshold", 1.5, "detourDistance", 20.0,
                    "rangedPreference", 1.5,
                    // Phase 7 additions:
                    "retreatHealSkill", 1.3, "projectileZoningTendency", 1.3
            )),

    RUSHER("Rusher",
            "Speed demon. Rushes mid immediately, skips own island chests entirely.",
            buildModifiers(
                    "BRIDGE_TO_MID", 3.0, "LOOT_OWN_ISLAND", 0.2,
                    "bridgeSpeed", 1.2, "pearlPriority", 1.5,
                    // Phase 7 additions:
                    "jumpBridgeChance", 1.5, "approachPatienceTicks", 0.3
            )),

    CAMPER("Camper",
            "Fortifies a position, watches bridges, waits for enemies to approach.",
            buildModifiers(
                    "CAMP", 2.5, "HUNT", 0.3, "BRIDGE_TO_PLAYER", 0.3,
                    "rangedPreference", 1.5, "fortifyPriority", 2.0,
                    "bridgeBreakPriority", 1.8,
                    // Phase 7 additions:
                    "bridgeCutSkill", 1.5, "highGroundPriority", 1.5
            )),

    STRATEGIC("Strategic",
            "Big brain player. Optimal decisions, reads enemy gear, uses environment.",
            buildModifiers(
                    "decisionQuality", 1.3, "awarenessRadius", 1.2,
                    "pearlSaveForClutch", 1.5, "lootDenial", 1.5,
                    "environmentalKillPriority", 1.5,
                    // Phase 7 additions:
                    "counterPlayIQ", 1.5, "baitDetectionSkill", 1.3, "thirdPartyTendency", 1.5
            )),

    COLLECTOR("Collector",
            "Loot goblin. Systematically loots every chest. Fights only when fully geared.",
            buildModifiers(
                    "LOOT", 2.5, "EQUIP", 1.5, "ENCHANT", 1.5,
                    "FIGHT", 0.6, "FIGHT_WHEN_GEARED", 1.2,
                    "inventoryManageSkill", 1.2,
                    // Phase 7 additions:
                    "islandRotationTendency", 2.0
            )),

    BERSERKER("Berserker",
            "All-in warrior. Never retreats, burns golden apples, charges into everything.",
            buildModifiers(
                    "FLEE", 0.05, "FIGHT", 1.5,
                    "fleeHealthThreshold", 0.1, "goldenAppleAggression", 1.8,
                    "multiTargetWillingness", 2.0,
                    // Phase 7 additions:
                    "retreatHealSkill", 0.1, "kbCancelSkill", 1.2, "thirdPartyTendency", 0.5
            )),

    SNIPER("Sniper",
            "Ranged specialist. Bow-spam, rod-combo, keeps distance. Melee only when forced.",
            buildModifiers(
                    "rangedPreference", 2.5, "projectileAccuracy", 1.2,
                    "meleePreference", 0.6, "optimalRange", 1.5,
                    "elevationPriority", 1.5,
                    // Phase 7 additions:
                    "verticalApproachTendency", 1.5, "projectileZoningTendency", 1.5
            )),

    TRICKSTER("Trickster",
            "Dirty tricks specialist. Pearl plays, traps, bridge breaking, fake retreats.",
            buildModifiers(
                    "pearlUsageIQ", 1.5, "rodUsageSkill", 1.4,
                    "BREAK_ENEMY_BRIDGE", 1.8, "USE_ENDER_PEARL", 1.5,
                    "fakeRetreatChance", 0.3, "trapPlacementPriority", 1.5,
                    "creativePlayFrequency", 2.0,
                    // Phase 7 additions:
                    "fakeBridgeChance", 2.0, "baitDetectionSkill", 1.3, "splitPathChance", 1.5
            )),

    CAUTIOUS("Cautious",
            "Careful player. Checks surroundings, crouches near edges, never rushes.",
            buildModifiers(
                    "mistakeFrequency", 0.5, "reactionTime", 0.9,
                    "FLEE", 1.3, "edgeCaution", 2.0,
                    "surroundingCheckFrequency", 2.0, "bridgeSafetyRails", 1.0,
                    "engageDelay", 1.5,
                    // Phase 7 additions:
                    "bridgeSafetyRailChance", 2.0, "baitDetectionSkill", 1.3, "retreatHealSkill", 1.3
            )),

    CLUTCH_MASTER("ClutchMaster",
            "Clutch expert. Water MLG, block clutching, pearl saves, stays calm under pressure.",
            buildModifiers(
                    "waterBucketMLG", 1.5, "pearlEscapeIQ", 2.0,
                    "blockClutchSpeed", 1.3, "panicAimReduction", 0.0,
                    "hotbarWaterBucketSlot", 2.0,
                    // Phase 7 additions:
                    "kbCancelSkill", 1.5
            )),

    /**
     * In team modes, actively supports teammates. Shares loot, bodyblocks, focuses same target.
     */
    TEAMWORK("Teamwork",
            "Team player. Shares loot, bodyblocks, focuses same target as teammates.",
            buildModifiers(
                    "teamUtility", 2.0, "lootSharing", 1.5,
                    "targetFocusSynergy", 1.5, "bodyblockWillingness", 1.5
            ));


    // ─── Instance Fields ────────────────────────────────────────

    private final String displayName;
    private final String description;
    private final Map<String, Double> modifiers;

    /**
     * Constructs a Personality enum constant.
     *
     * @param displayName human-readable name
     * @param description brief description of behavior
     * @param modifiers   map of modifier keys to multiplier values
     */
    Personality(@Nonnull String displayName, @Nonnull String description,
                @Nonnull Map<String, Double> modifiers) {
        this.displayName = displayName;
        this.description = description;
        this.modifiers = modifiers;
    }

    /**
     * Returns the human-readable display name.
     *
     * @return the display name
     */
    @Nonnull
    public String getDisplayName() {
        return displayName;
    }

    /**
     * Returns the personality description.
     *
     * @return the description
     */
    @Nonnull
    public String getDescription() {
        return description;
    }

    /**
     * Returns all modifiers for this personality. Keys are either utility action
     * names (e.g., "FIGHT", "LOOT") or parameter names (e.g., "fleeHealthThreshold").
     *
     * @return unmodifiable map of modifier keys to multiplier values
     */
    @Nonnull
    public Map<String, Double> getModifiers() {
        return modifiers;
    }

    /**
     * Returns the multiplier for a given key, or 1.0 if not specified.
     *
     * @param key the modifier key
     * @return the multiplier value (default 1.0)
     */
    public double getModifier(@Nonnull String key) {
        return modifiers.getOrDefault(key, 1.0);
    }

    /**
     * Attempts to parse a Personality from a string name (case-insensitive).
     * Tries both the enum name and the display name.
     *
     * @param name the name to parse
     * @return the Personality, or null if not found
     */
    @Nullable
    public static Personality fromString(@Nullable String name) {
        if (name == null) return null;
        String upper = name.toUpperCase().replace(" ", "_");
        for (Personality p : values()) {
            if (p.name().equals(upper) || p.displayName.equalsIgnoreCase(name)) {
                return p;
            }
        }
        return null;
    }

    // ─── Helper ─────────────────────────────────────────────────

    /**
     * Builds a modifiers map from varargs key-value pairs.
     * Each pair is (String key, Double value).
     */
    private static Map<String, Double> buildModifiers(Object... args) {
        Map<String, Double> map = new java.util.LinkedHashMap<>();
        for (int i = 0; i < args.length; i += 2) {
            String key = (String) args[i];
            double value = ((Number) args[i + 1]).doubleValue();
            map.put(key, value);
        }
        return java.util.Collections.unmodifiableMap(map);
    }
}
