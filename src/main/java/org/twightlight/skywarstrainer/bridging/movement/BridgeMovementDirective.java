package org.twightlight.skywarstrainer.bridging.movement;

import javax.annotation.Nonnull;

/**
 * An advisory directive from the {@link BridgeMovementController} to the
 * {@link org.twightlight.skywarstrainer.bridging.BridgeEngine}.
 *
 * <p>This object communicates the movement controller's DESIRED movement state
 * to the BridgeEngine. The engine then feeds this into the active BridgeStrategy
 * via its tick method, allowing the strategy to ACCEPT or REJECT the movement
 * advice while retaining authority over block placement.</p>
 *
 * <p><b>This resolves the Phase 7 collision issue:</b> Previously, the
 * BridgeMovementController directly called MovementController.setSneaking() etc.,
 * which conflicted with BridgeStrategy also setting those same states. Now the
 * movement controller only produces a directive, and the strategy integrates it.</p>
 */
public class BridgeMovementDirective {

    /** Whether the movement overlay wants the bot to be sneaking. */
    public boolean requestSneak = false;

    /** Whether the movement overlay wants the bot to be sprinting. */
    public boolean requestSprint = false;

    /** Whether the movement overlay wants the bot to jump this tick. */
    public boolean requestJump = false;

    /** Whether the movement overlay wants to PAUSE block placement this tick. */
    public boolean pausePlacement = false;

    /** Whether the movement overlay wants to place a SIDE block (safety rail). */
    public boolean placeSideBlock = false;

    /** The side to place the rail block on: -1 = left, +1 = right, 0 = none. */
    public int sideBlockDirection = 0;

    /** Target pitch override for the movement overlay, or NaN if no override. */
    public float pitchOverride = Float.NaN;

    /** Speed multiplier applied by this movement type (1.0 = normal). */
    public double speedMultiplier = 1.0;

    /** Fail rate multiplier applied by this movement type (1.0 = normal). */
    public double failRateMultiplier = 1.0;

    /** The active movement type name for debug logging. */
    @Nonnull
    public String movementTypeName = "SAFE_SNEAK";

    /**
     * Resets all fields to defaults (no override).
     */
    public void reset() {
        requestSneak = false;
        requestSprint = false;
        requestJump = false;
        pausePlacement = false;
        placeSideBlock = false;
        sideBlockDirection = 0;
        pitchOverride = Float.NaN;
        speedMultiplier = 1.0;
        failRateMultiplier = 1.0;
        movementTypeName = "SAFE_SNEAK";
    }
}
