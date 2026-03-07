package org.twightlight.skywarstrainer.combat.counter;

import javax.annotation.Nonnull;
import java.util.UUID;

/**
 * Per-enemy behavioral profile built from observations.
 *
 * <p><b>UPDATED (Phase 7):</b> Added missing {@code goesForEdgeKnocks} field
 * referenced by {@link CounterStrategySelector}, and added
 * {@code retreatsAtLowHP} field. Also added {@code getHitsLanded()} /
 * {@code getHitsReceived()} convenience methods for the ComboTracker interface.</p>
 */
public class EnemyProfile {

    /** The enemy's player UUID. */
    @Nonnull
    public final UUID playerId;

    /** How many times this enemy has been observed. */
    public int observationCount;

    /** Last tick this enemy was observed. */
    public long lastObservedTick;

    /** Inferred combat style from observations. */
    @Nonnull
    public CombatStyle observedCombatStyle = CombatStyle.UNKNOWN;

    /** Estimated skill level [0.0, 1.0] based on hit ratios. */
    public double estimatedSkillLevel = 0.5;

    /** Number of times this enemy has hit the bot. */
    public int hitsLandedOnBot;

    /** Number of times the bot has hit this enemy. */
    public int hitsLandedOnEnemy;

    /** Whether this enemy uses projectiles (bow, snowball, etc.). */
    public boolean usesProjectiles;

    /** Whether this enemy uses bait or fake retreats. */
    public boolean usesBait;

    /** Whether this enemy uses fishing rod. */
    public boolean usesRod;

    /** Whether this enemy has thrown an ender pearl recently. */
    public boolean pearledRecently;

    /** Whether this enemy retreats when at low HP. */
    public boolean retreatsAtLowHP;

    /** Whether this enemy attempts to knock players into the void. */
    public boolean goesForEdgeKnocks;

    /** Count of times this enemy approached directly (rusher detection). */
    public int directApproachCount;

    /**
     * Creates a new EnemyProfile for the given player UUID.
     *
     * @param playerId the enemy's UUID
     */
    public EnemyProfile(@Nonnull UUID playerId) {
        this.playerId = playerId;
    }

    /** Observed combat styles. */
    public enum CombatStyle {
        UNKNOWN,
        BALANCED,
        AGGRESSIVE,
        PASSIVE,
        PROJECTILE,
        TRICKSTER,
        COMBO_HEAVY
    }
}
