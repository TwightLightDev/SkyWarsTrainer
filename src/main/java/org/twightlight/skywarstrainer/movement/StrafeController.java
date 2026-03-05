package org.twightlight.skywarstrainer.movement;

import org.bukkit.util.Vector;
import org.twightlight.skywarstrainer.bot.TrainerBot;
import org.twightlight.skywarstrainer.config.DifficultyConfig.DifficultyProfile;
import org.twightlight.skywarstrainer.util.RandomUtil;

import javax.annotation.Nonnull;

/**
 * Controls A/D strafing behavior during combat and general movement.
 *
 * <p>Strafing is a critical PvP mechanic in 1.8: players move side-to-side
 * while attacking to dodge hits and make themselves harder to target.
 * The quality and unpredictability of strafing is a key skill differentiator.</p>
 *
 * <p>Strafe behavior is controlled by two difficulty parameters:
 * <ul>
 *   <li>{@code strafeIntensity} (0.0–1.0): How fast the bot strafes (speed of lateral movement).</li>
 *   <li>{@code strafeUnpredictability} (0.0–1.0): How random the strafe pattern is.
 *       Low = simple left-right-left-right. High = random direction, timing, and speed.</li>
 * </ul></p>
 */
public class StrafeController {

    private final TrainerBot bot;

    /** Whether strafing is currently active. */
    private boolean strafing;

    /**
     * Current strafe direction: -1 = left, +1 = right, 0 = no strafe.
     * Fractional values allow smooth transitions.
     */
    private double strafeDirection;

    /** Ticks remaining before the next direction change. */
    private int directionChangeTicks;

    /** Current strafe speed factor (0.0–1.0). */
    private double currentIntensity;

    /**
     * Creates a new StrafeController for the given bot.
     *
     * @param bot the owning trainer bot
     */
    public StrafeController(@Nonnull TrainerBot bot) {
        this.bot = bot;
        this.strafing = false;
        this.strafeDirection = 0;
        this.directionChangeTicks = 0;
        this.currentIntensity = 0;
    }

    /**
     * Activates strafing. Called by combat engine when engaging an enemy.
     */
    public void startStrafing() {
        if (strafing) return;
        strafing = true;
        chooseNewDirection();
    }

    /**
     * Deactivates strafing.
     */
    public void stopStrafing() {
        strafing = false;
        strafeDirection = 0;
        currentIntensity = 0;
    }

    /**
     * Tick method. Updates strafe direction changes based on timing
     * and unpredictability parameters.
     */
    public void tick() {
        if (!strafing) return;

        DifficultyProfile diff = bot.getDifficultyProfile();
        currentIntensity = diff.getStrafeIntensity();

        directionChangeTicks--;
        if (directionChangeTicks <= 0) {
            chooseNewDirection();
        }
    }

    /**
     * Selects a new strafe direction and schedules the next change.
     * The randomness of direction choice is controlled by strafeUnpredictability.
     */
    private void chooseNewDirection() {
        DifficultyProfile diff = bot.getDifficultyProfile();
        double unpredictability = diff.getStrafeUnpredictability();

        if (unpredictability < 0.3) {
            // Low unpredictability: simple alternation
            strafeDirection = (strafeDirection <= 0) ? 1.0 : -1.0;
            directionChangeTicks = RandomUtil.nextInt(8, 15);
        } else if (unpredictability < 0.6) {
            // Medium: varied timing, occasional double-strafe
            if (RandomUtil.chance(0.3)) {
                // Double-strafe: stay in same direction
                directionChangeTicks = RandomUtil.nextInt(3, 8);
            } else {
                strafeDirection = (strafeDirection <= 0) ? 1.0 : -1.0;
                directionChangeTicks = RandomUtil.nextInt(5, 12);
            }
        } else {
            // High: completely random direction, speed, and timing
            strafeDirection = RandomUtil.nextDouble(-1.0, 1.0);
            if (RandomUtil.chance(0.15)) {
                // Backward strafe component
                strafeDirection *= 0.5;
            }
            directionChangeTicks = RandomUtil.nextInt(3, 10);
        }
    }

    /**
     * Returns the current strafe velocity vector relative to the bot's
     * facing direction. This should be added to the bot's movement each tick.
     *
     * @param rightDirection the bot's right-direction unit vector
     * @return the strafe velocity component
     */
    @Nonnull
    public Vector getStrafeVelocity(@Nonnull Vector rightDirection) {
        if (!strafing || currentIntensity <= 0) {
            return new Vector(0, 0, 0);
        }

        // Scale by intensity (0.0–1.0) and direction (-1 to +1)
        double strafeSpeed = strafeDirection * currentIntensity * 0.08; // Max ~0.08 blocks/tick lateral
        return rightDirection.clone().multiply(strafeSpeed);
    }

    /** @return true if strafing is active */
    public boolean isStrafing() { return strafing; }

    /** @return the current strafe direction (-1 left, +1 right) */
    public double getStrafeDirection() { return strafeDirection; }

    /** @return the current strafe intensity factor */
    public double getCurrentIntensity() { return currentIntensity; }
}

