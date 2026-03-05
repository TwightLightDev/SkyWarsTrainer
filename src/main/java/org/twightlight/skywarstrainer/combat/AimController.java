package org.twightlight.skywarstrainer.combat;

import org.bukkit.Location;
import org.bukkit.entity.LivingEntity;
import org.bukkit.util.Vector;
import org.twightlight.skywarstrainer.bot.TrainerBot;
import org.twightlight.skywarstrainer.config.DifficultyConfig.DifficultyProfile;
import org.twightlight.skywarstrainer.util.MathUtil;
import org.twightlight.skywarstrainer.util.RandomUtil;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * Simulates realistic head movement toward a target with difficulty-scaled imprecision.
 *
 * <p>The AimController calculates the desired yaw/pitch to look at a target, then
 * applies maximum rotation speed limits, Gaussian noise, exponential smoothing,
 * and periodic aim flicks to simulate human-like aiming behavior.</p>
 *
 * <h3>Aim pipeline per tick:</h3>
 * <ol>
 *   <li>Calculate desired angles to target's body center</li>
 *   <li>At HARD+: apply predictive aim (lead the target based on velocity)</li>
 *   <li>Add Gaussian noise scaled by (1 - aimAccuracy)</li>
 *   <li>Clamp rotation per tick by aimSpeedDegPerTick</li>
 *   <li>Apply exponential smoothing for natural acceleration/deceleration</li>
 *   <li>Add passive head movement noise when not aiming</li>
 * </ol>
 */
public class AimController {

    private final TrainerBot bot;

    /** Current aimed yaw (where the bot is looking). */
    private float currentYaw;
    /** Current aimed pitch. */
    private float currentPitch;

    /** The current aim target entity. */
    private LivingEntity target;

    /** Timer for periodic aim "flick" corrections at high difficulty. */
    private int flickTimer;
    /** Ticks between flicks (randomized). */
    private int flickInterval;

    /** Cached noise values that drift slowly (not re-rolled every tick). */
    private double driftNoiseYaw;
    private double driftNoisePitch;

    /**
     * Creates an AimController for the given bot.
     *
     * @param bot the owning trainer bot
     */
    public AimController(@Nonnull TrainerBot bot) {
        this.bot = bot;
        LivingEntity entity = bot.getLivingEntity();
        if (entity != null) {
            Location loc = entity.getLocation();
            this.currentYaw = loc.getYaw();
            this.currentPitch = loc.getPitch();
        }
        this.flickTimer = 0;
        this.flickInterval = RandomUtil.nextInt(15, 40);
        this.driftNoiseYaw = 0;
        this.driftNoisePitch = 0;
    }

    /**
     * Sets the current aim target.
     *
     * @param target the entity to aim at, or null to clear target
     */
    public void setTarget(@Nullable LivingEntity target) {
        this.target = target;
    }

    /**
     * Returns the current aim target.
     *
     * @return the target entity, or null if none
     */
    @Nullable
    public LivingEntity getTarget() {
        return target;
    }

    /**
     * Ticks the aim controller. Updates yaw/pitch toward the target with
     * difficulty-appropriate imprecision and speed limits.
     *
     * <p>If no target is set, applies passive head movement noise only.</p>
     */
    public void tick() {
        LivingEntity botEntity = bot.getLivingEntity();
        if (botEntity == null) return;

        DifficultyProfile diff = bot.getDifficultyProfile();

        if (target == null || target.isDead()) {
            // No target: apply idle head movement noise
            applyIdleNoise(diff);
            applyLookAngles(botEntity);
            return;
        }

        // Calculate desired angles to target
        Location eyePos = botEntity.getEyeLocation();
        Location targetPos = getAimPoint(target, diff);

        float desiredYaw = MathUtil.calculateYaw(eyePos, targetPos);
        float desiredPitch = MathUtil.calculatePitch(eyePos, targetPos);

        // Add accuracy-based noise
        double noiseScale = 1.0 - diff.getAimAccuracy();
        double noiseYaw = RandomUtil.gaussian(0, noiseScale * 8.0);
        double noisePitch = RandomUtil.gaussian(0, noiseScale * 4.0);
        desiredYaw += (float) noiseYaw;
        desiredPitch += (float) noisePitch;

        // Calculate angular difference
        double yawDiff = MathUtil.angleDifference(currentYaw, desiredYaw);
        double pitchDiff = desiredPitch - currentPitch;

        // Clamp rotation speed per tick
        double maxSpeed = diff.getAimSpeedDegPerTick();
        yawDiff = MathUtil.clamp(yawDiff, -maxSpeed, maxSpeed);
        pitchDiff = MathUtil.clamp(pitchDiff, -maxSpeed * 0.7, maxSpeed * 0.7);

        // Exponential smoothing for natural feel
        double smoothFactor = MathUtil.clamp(maxSpeed / 30.0, 0.1, 0.95);
        currentYaw += (float) (yawDiff * smoothFactor);
        currentPitch += (float) (pitchDiff * smoothFactor);

        // Clamp pitch to valid range
        currentPitch = MathUtil.clamp(currentPitch, -90f, 90f);

        // Periodic aim flick at HARD+ difficulty
        flickTimer++;
        if (flickTimer >= flickInterval && diff.getAimAccuracy() >= 0.8) {
            // Sudden micro-correction toward exact target position
            float exactYaw = MathUtil.calculateYaw(eyePos, targetPos);
            float exactPitch = MathUtil.calculatePitch(eyePos, targetPos);
            currentYaw = (float) MathUtil.exponentialSmooth(currentYaw, exactYaw, 0.6);
            currentPitch = (float) MathUtil.exponentialSmooth(currentPitch, exactPitch, 0.6);
            flickTimer = 0;
            flickInterval = RandomUtil.nextInt(10, 30);
        }

        applyLookAngles(botEntity);
    }

    /**
     * Calculates the aim point on the target. At HARD+ difficulty, leads the target
     * based on velocity for predictive aim.
     */
    @Nonnull
    private Location getAimPoint(@Nonnull LivingEntity target, @Nonnull DifficultyProfile diff) {
        // Aim at body center (1 block above feet)
        Location bodyCenter = target.getLocation().add(0, 1.0, 0);

        // Predictive aim at HARD+ (aimAccuracy >= 0.8)
        if (diff.getAimAccuracy() >= 0.8) {
            Vector velocity = target.getVelocity();
            // Lead by a few ticks worth of movement
            double leadTicks = 3.0 * diff.getAimAccuracy();
            bodyCenter.add(velocity.clone().multiply(leadTicks));
        }

        return bodyCenter;
    }

    /**
     * Applies idle head movement noise when not aiming at a target.
     * Simulates the subtle head movement real players exhibit.
     */
    private void applyIdleNoise(@Nonnull DifficultyProfile diff) {
        double noiseMag = diff.getHeadMovementNoise();

        // Drift noise changes slowly (smoothed random walk)
        driftNoiseYaw += RandomUtil.gaussian(0, noiseMag * 2.0);
        driftNoisePitch += RandomUtil.gaussian(0, noiseMag * 1.0);

        // Decay toward zero to prevent unbounded drift
        driftNoiseYaw *= 0.92;
        driftNoisePitch *= 0.92;

        currentYaw += (float) driftNoiseYaw;
        currentPitch += (float) driftNoisePitch;
        currentPitch = MathUtil.clamp(currentPitch, -90f, 90f);
    }

    /**
     * Applies the calculated yaw/pitch to the bot's entity.
     */
    private void applyLookAngles(@Nonnull LivingEntity entity) {
        Location loc = entity.getLocation();
        loc.setYaw(currentYaw);
        loc.setPitch(currentPitch);
        entity.teleport(loc);
    }

    /**
     * Returns the angular error between current aim and the target in degrees.
     * Used by ClickController to determine if a click would "hit".
     *
     * @return the total angular error, or Double.MAX_VALUE if no target
     */
    public double getAimError() {
        if (target == null || target.isDead()) return Double.MAX_VALUE;
        LivingEntity entity = bot.getLivingEntity();
        if (entity == null) return Double.MAX_VALUE;

        Location eyePos = entity.getEyeLocation();
        Location targetPos = target.getLocation().add(0, 1.0, 0);

        float idealYaw = MathUtil.calculateYaw(eyePos, targetPos);
        float idealPitch = MathUtil.calculatePitch(eyePos, targetPos);

        double yawErr = Math.abs(MathUtil.angleDifference(currentYaw, idealYaw));
        double pitchErr = Math.abs(currentPitch - idealPitch);

        return Math.sqrt(yawErr * yawErr + pitchErr * pitchErr);
    }

    /** @return current yaw the bot is looking at */
    public float getCurrentYaw() { return currentYaw; }

    /** @return current pitch the bot is looking at */
    public float getCurrentPitch() { return currentPitch; }

    /** Sets the current yaw directly (used by movement controller). */
    public void setCurrentYaw(float yaw) { this.currentYaw = yaw; }

    /** Sets the current pitch directly. */
    public void setCurrentPitch(float pitch) { this.currentPitch = pitch; }
}

