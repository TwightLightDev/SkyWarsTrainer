package org.twightlight.skywarstrainer.bridging.movement;

/**
 * Enumerates the high-level movement techniques the bot can use
 * while bridging. These overlay on top of the existing bridge
 * strategies (NormalBridge, SpeedBridge, etc.) to control HOW
 * the bot physically moves during construction.
 */
public enum BridgeMovementType {

    /**
     * Standard sneak-backward bridging. Slowest, safest. This is the
     * default wrapper that just lets the underlying BridgeStrategy handle
     * everything. Used with NormalBridge and others at low difficulty.
     */
    SAFE_SNEAK(1.0, 1.0),

    /**
     * Sprint between placements, sneak only at block edge. Faster than
     * safe sneak but requires better timing to avoid falling.
     */
    SPEED_SPRINT(1.5, 1.3),

    /**
     * Sprint → jump → place block mid-air → land on placed block → repeat.
     * Fastest ground bridging technique. Much higher fail rate.
     */
    JUMP_BRIDGE(2.0, 2.0),

    /**
     * Jump → place block under feet → repeat for height gain. Used when
     * destination is above the bot. Combines with forward movement for
     * a staircase rather than a pillar.
     */
    STAIR_CLIMB(0.8, 1.5),

    /**
     * Fake bridge to bait enemy attacks. Place 1-3 blocks toward an
     * enemy, stop, wait for them to commit, then counter or retreat.
     */
    BAIT_BRIDGE(0.3, 1.0),

    /**
     * While bridging, periodically place blocks on the sides of the
     * bridge for protection against knockback into the void.
     */
    SAFETY_RAIL(0.85, 0.9);

    /** Multiplier applied to the base bridge strategy's speed. */
    private final double speedMultiplier;

    /** Multiplier applied to the base bridge fail rate. */
    private final double failRateMultiplier;

    BridgeMovementType(double speedMultiplier, double failRateMultiplier) {
        this.speedMultiplier = speedMultiplier;
        this.failRateMultiplier = failRateMultiplier;
    }

    /**
     * Returns the speed multiplier this movement type applies.
     *
     * @return the speed multiplier (>1.0 = faster, <1.0 = slower)
     */
    public double getSpeedMultiplier() {
        return speedMultiplier;
    }

    /**
     * Returns the fail rate multiplier.
     *
     * @return the fail rate multiplier (>1.0 = more failures)
     */
    public double getFailRateMultiplier() {
        return failRateMultiplier;
    }
}
