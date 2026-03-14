package org.twightlight.skywarstrainer.movement;

import org.bukkit.entity.LivingEntity;
import org.bukkit.util.Vector;
import org.twightlight.skywarstrainer.bot.TrainerBot;
import org.twightlight.skywarstrainer.config.DifficultyConfig.DifficultyProfile;
import org.twightlight.skywarstrainer.util.MathUtil;
import org.twightlight.skywarstrainer.util.RandomUtil;

import javax.annotation.Nonnull;

/**
 * Simulates human-like movement imperfections to make bot motion look natural.
 *
 * <p>Core principle: NEVER move in perfectly straight lines or at constant speed.
 * Real players exhibit subtle lateral deviation, speed variation, direction wobble,
 * and smooth acceleration/deceleration curves. This simulator injects all of these.</p>
 *
 * <p>The magnitude of noise is scaled by {@code (1 - difficulty)}. EXPERT bots have
 * minimal noise but still aren't perfectly robotic. BEGINNER bots have significant
 * movement imprecision.</p>
 *
 * <p>Noise types applied:
 * <ul>
 *   <li>Position noise: ±0.02–0.08 blocks lateral deviation per tick</li>
 *   <li>Speed noise: ±5–15% speed variation per tick</li>
 *   <li>Direction noise: ±1–3 degrees yaw deviation per tick</li>
 *   <li>Acceleration curves: ramp-up over 3-5 ticks when starting, deceleration when stopping</li>
 * </ul></p>
 */
public class HumanMotionSimulator {

    private final TrainerBot bot;

    /**
     * Current acceleration factor. Ramps from 0.0 to 1.0 when the bot starts
     * moving, providing smooth acceleration.
     */
    private double accelerationFactor;

    /**
     * Ticks since movement started. Used for acceleration ramp-up.
     */
    private int movementTicks;

    /** Whether the bot was moving last tick (for detecting start/stop transitions). */
    private boolean wasMoving;

    /**
     * Creates a new HumanMotionSimulator for the given bot.
     *
     * @param bot the owning trainer bot
     */
    public HumanMotionSimulator(@Nonnull TrainerBot bot) {
        this.bot = bot;
        this.accelerationFactor = 0.0;
        this.movementTicks = 0;
        this.wasMoving = false;
    }

    /**
     * Processes a movement command and returns the final velocity vector with
     * human-like noise and acceleration applied.
     *
     * @param entity        the bot's living entity
     * @param direction     the intended movement direction (unit vector, Y=0)
     * @param targetSpeed   the desired movement speed in blocks/tick
     * @return the processed velocity vector with noise applied
     */
    @Nonnull
    public Vector processMovement(@Nonnull LivingEntity entity, @Nonnull Vector direction,
                                  double targetSpeed) {
        DifficultyProfile diff = bot.getDifficultyProfile();
        double difficultyFraction = diff.getDifficulty().asFraction();

        // Noise scale: high for beginners, low for experts
        double noiseScale = 1.0 - difficultyFraction;

        // ── Acceleration ──
        if (!wasMoving) {
            // Just started moving — reset acceleration
            movementTicks = 0;
            accelerationFactor = 0.0;
        }
        wasMoving = true;
        movementTicks++;

        // Ramp-up: 3-5 ticks to reach full speed
        int rampTicks = (int) Math.round(MathUtil.lerp(5.0, 2.0, difficultyFraction));
        if (movementTicks < rampTicks) {
            accelerationFactor = MathUtil.smoothStep(0, rampTicks, movementTicks);
        } else {
            accelerationFactor = 1.0;
        }

        // Apply acceleration to speed
        double effectiveSpeed = targetSpeed * accelerationFactor;

        // ── Speed noise: ±5-15% variation ──
        double speedNoiseRange = MathUtil.lerp(0.15, 0.05, difficultyFraction);
        double speedNoise = 1.0 + RandomUtil.gaussian(0.0, speedNoiseRange);
        effectiveSpeed *= MathUtil.clamp(speedNoise, 0.5, 1.5);

        // ── Direction noise: ±1-3 degrees yaw deviation ──
        double directionNoiseRange = MathUtil.lerp(3.0, 0.5, difficultyFraction);
        double noiseAngle = RandomUtil.gaussian(0.0, directionNoiseRange);
        double noiseRad = Math.toRadians(noiseAngle);

        // Rotate direction vector by noise angle (Y-axis rotation)
        double cos = Math.cos(noiseRad);
        double sin = Math.sin(noiseRad);
        double newX = direction.getX() * cos - direction.getZ() * sin;
        double newZ = direction.getX() * sin + direction.getZ() * cos;
        Vector noisyDirection = new Vector(newX, 0, newZ);
        if (noisyDirection.lengthSquared() > 0.001) {
            noisyDirection.normalize();
        }

        // ── Position noise: lateral deviation ──
        double lateralNoiseRange = MathUtil.lerp(0.08, 0.02, difficultyFraction);
        double lateralNoise = RandomUtil.gaussian(0.0, lateralNoiseRange);

        // Create perpendicular vector for lateral offset
        Vector lateral = new Vector(-noisyDirection.getZ(), 0, noisyDirection.getX());
        lateral.multiply(lateralNoise);

        // Final velocity = direction * speed + lateral noise
        Vector velocity = noisyDirection.multiply(effectiveSpeed).add(lateral);

        return velocity;
    }

    /**
     * Applies smooth deceleration when the bot stops moving. Over 2-3 ticks,
     * the bot's horizontal velocity reduces to zero.
     *
     * @param entity the bot's living entity
     */
    public void applyDeceleration(@Nonnull LivingEntity entity) {
        if (!wasMoving) return;

        Vector currentVel = entity.getVelocity();
        double horizontalSpeed = Math.sqrt(currentVel.getX() * currentVel.getX()
                + currentVel.getZ() * currentVel.getZ());

        if (horizontalSpeed < 0.01) {
            // [FIX-2D] Fully zero out horizontal velocity to prevent drift
            currentVel.setX(0);
            currentVel.setZ(0);
            entity.setVelocity(currentVel);
            wasMoving = false;
            accelerationFactor = 0.0;
            return;
        }

        DifficultyProfile diff = bot.getDifficultyProfile();
        double decelFactor = MathUtil.lerp(0.4, 0.6, diff.getDifficulty().asFraction());

        currentVel.setX(currentVel.getX() * decelFactor);
        currentVel.setZ(currentVel.getZ() * decelFactor);
        entity.setVelocity(currentVel);

        // [FIX-2D] Lowered threshold to match the early-return check
        if (horizontalSpeed * decelFactor < 0.01) {
            wasMoving = false;
            accelerationFactor = 0.0;
        }
    }


    /**
     * Returns the current acceleration factor (0.0 = just started, 1.0 = full speed).
     *
     * @return the acceleration factor
     */
    public double getAccelerationFactor() {
        return accelerationFactor;
    }

    /**
     * Returns whether the simulator considers the bot to be currently in motion.
     *
     * @return true if the bot was moving as of the last processing call
     */
    public boolean isMoving() {
        return wasMoving;
    }
}
