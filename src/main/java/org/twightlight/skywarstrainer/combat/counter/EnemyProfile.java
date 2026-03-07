package org.twightlight.skywarstrainer.combat.counter;

import javax.annotation.Nonnull;
import java.util.UUID;

/**
 * Data class tracking observed behavior of a single enemy player. Built up
 * over time from observations (ThreatMap data, combat interactions, movement
 * patterns).
 *
 * <p>This profile is NOT the player's actual skill — it's the bot's PERCEPTION
 * of the player, which improves over time and with higher {@code counterPlayIQ}.
 * A low-IQ bot may misidentify a player's style.</p>
 */
public class EnemyProfile {

    /** The enemy player's UUID. */
    @Nonnull
    public final UUID playerId;

    // ── Approach Observation ──

    /** How many times this enemy has approached directly (rushed). */
    public int directApproachCount;

    /** How many times this enemy used non-direct approaches (diagonal, flanking). */
    public int indirectApproachCount;

    // ── Combat Observation ──

    /** The observed combat style inferred from behavior. */
    @Nonnull
    public CombatStyle observedCombatStyle;

    /** Estimated skill level 0.0 (bad) to 1.0 (excellent). */
    public double estimatedSkillLevel;

    /** Total hits this enemy has landed on the bot. */
    public int hitsLandedOnBot;

    /** Total hits the bot has landed on this enemy. */
    public int hitsLandedOnEnemy;

    /** Whether this enemy has been observed using bait/fake retreats. */
    public boolean usesBait;

    /** Whether this enemy frequently uses projectiles. */
    public boolean usesProjectiles;

    /** Whether this enemy uses fishing rod combos. */
    public boolean usesRod;

    /** Average HP fraction at which this enemy typically engages combat. */
    public double avgEngageHP;

    /** Whether this enemy tends to knock players toward void edges. */
    public boolean goesForEdgeKnocks;

    /** Whether this enemy has used an ender pearl recently. */
    public boolean pearledRecently;

    /** Observed gear quality estimate 0.0-1.0 */
    public double equipmentLevel;

    /** Whether this enemy tends to retreat at low HP. */
    public boolean retreatsAtLowHP;

    /** The tick at which this enemy was last observed. */
    public long lastObservedTick;

    /** Total observation updates made for this enemy. */
    public int observationCount;

    /**
     * Creates a new EnemyProfile with default (unknown) values.
     *
     * @param playerId the enemy player UUID
     */
    public EnemyProfile(@Nonnull UUID playerId) {
        this.playerId = playerId;
        this.observedCombatStyle = CombatStyle.UNKNOWN;
        this.estimatedSkillLevel = 0.5; // Assume average until proven otherwise
        this.avgEngageHP = 1.0;
        this.equipmentLevel = 0.5;
        this.lastObservedTick = 0;
        this.observationCount = 0;
    }

    /**
     * Returns the inferred approach style based on observed approach counts.
     *
     * @return RUSHER if mostly direct, STRATEGIC if mostly indirect, UNKNOWN if insufficient data
     */
    @Nonnull
    public ApproachStyle getInferredApproachStyle() {
        int total = directApproachCount + indirectApproachCount;
        if (total < 2) return ApproachStyle.UNKNOWN;
        double directRatio = (double) directApproachCount / total;
        if (directRatio > 0.7) return ApproachStyle.RUSHER;
        if (directRatio < 0.3) return ApproachStyle.FLANKER;
        return ApproachStyle.MIXED;
    }

    @Override
    public String toString() {
        return "EnemyProfile{" + playerId.toString().substring(0, 8)
                + " style=" + observedCombatStyle
                + " skill=" + String.format("%.2f", estimatedSkillLevel)
                + " obs=" + observationCount + "}";
    }

    // ── Inner Enums ──

    /** Inferred combat style of the enemy. */
    public enum CombatStyle {
        UNKNOWN,
        AGGRESSIVE,    // Charges in, high CPS, lots of hits
        PASSIVE,       // Keeps distance, uses projectiles, retreats
        COMBO_HEAVY,   // Good at maintaining combos
        PROJECTILE,    // Primarily uses bow/snowball
        TRICKSTER,     // Uses bait, rods near void, fake retreats
        BALANCED       // No strong tendency
    }

    /** Inferred approach style. */
    public enum ApproachStyle {
        UNKNOWN,
        RUSHER,    // Mostly direct approaches
        FLANKER,   // Mostly indirect/diagonal approaches
        MIXED      // Uses both
    }
}
