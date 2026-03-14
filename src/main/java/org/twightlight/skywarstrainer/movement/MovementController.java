package org.twightlight.skywarstrainer.movement;

import org.bukkit.Location;
import org.bukkit.entity.LivingEntity;
import org.bukkit.util.Vector;
import org.twightlight.skywarstrainer.bot.TrainerBot;
import org.twightlight.skywarstrainer.config.DifficultyConfig.DifficultyProfile;
import org.twightlight.skywarstrainer.util.MathUtil;
import org.twightlight.skywarstrainer.util.NMSHelper;
import org.twightlight.skywarstrainer.util.PacketUtil;
import org.twightlight.skywarstrainer.util.RandomUtil;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * Central movement controller for a trainer bot.
 */
public class MovementController {

    private final TrainerBot bot;
    private final SprintController sprintController;
    private final JumpController jumpController;
    private final StrafeController strafeController;
    private final HumanMotionSimulator humanMotion;
    private final WaterMLGController waterMLGController;

    private Location moveTarget;
    private boolean movingForward;
    private boolean movingBackward;
    private boolean sneaking;
    private Location lookTarget;
    private float currentYaw;
    private float currentPitch;
    private boolean frozen;
    private double speedMultiplier;
    private MovementAuthority currentAuthority = MovementAuthority.NONE;
    private boolean sprintJumpEnabled;
    private static final double SPRINT_JUMP_MIN_DISTANCE = 5.0;
    private int ticksSinceLastSprintJump;
    private boolean inSprintJumpCycle;

    public MovementController(@Nonnull TrainerBot bot) {
        this.bot = bot;
        this.sprintController = new SprintController(bot);
        this.jumpController = new JumpController(bot);
        this.strafeController = new StrafeController(bot);
        this.humanMotion = new HumanMotionSimulator(bot);
        this.waterMLGController = new WaterMLGController(bot);
        this.moveTarget = null;
        this.movingForward = false;
        this.movingBackward = false;
        this.sneaking = false;
        this.lookTarget = null;
        this.frozen = false;
        this.speedMultiplier = 1.0;
        this.sprintJumpEnabled = true;
        this.ticksSinceLastSprintJump = 0;
        this.inSprintJumpCycle = false;

        LivingEntity entity = bot.getLivingEntity();
        if (entity != null) {
            this.currentYaw = entity.getLocation().getYaw();
            this.currentPitch = entity.getLocation().getPitch();
        }
    }

    // ─── Tick ───────────────────────────────────────────────────

    /**
     * Main tick method. Called every server tick for smooth movement.
     *
     * [FIX] Removed duplicate movement application block that was causing
     * the bot to receive double velocity every tick.
     */
    public void tick() {
        LivingEntity entity = bot.getLivingEntity();
        if (entity == null || entity.isDead()) return;

        // Update sub-controllers
        sprintController.tick();
        jumpController.tick();
        strafeController.tick();
        waterMLGController.tick();

        // Update look direction
        updateLookDirection(entity);

        if (frozen) {
            applyLookToEntity(entity);
            return;
        }

        // [FIX] Single movement application block — was duplicated before
        if (moveTarget != null || movingForward || movingBackward) {
            updateSprintJumpTravel(entity);
            applyMovement(entity);
        } else if (strafeController.isStrafing()) {
            // Only strafe when not moving toward a target
            Vector rightDir = getRightDirection();
            Vector strafeVelocity = strafeController.getStrafeVelocity(rightDir);
            strafeVelocity.setY(entity.getVelocity().getY());
            entity.setVelocity(strafeVelocity);
        } else {
            inSprintJumpCycle = false;
            humanMotion.applyDeceleration(entity);
        }

        updateSneakState(entity);
        applyLookToEntity(entity);
    }

    // ─── Sprint-Jump Travel ─────────────────────────────────────

    private void updateSprintJumpTravel(@Nonnull LivingEntity entity) {
        ticksSinceLastSprintJump++;

        if (!sprintJumpEnabled || sneaking || movingBackward) {
            inSprintJumpCycle = false;
            return;
        }

        double distanceToTarget = 0;
        if (moveTarget != null) {
            Location botLoc = entity.getLocation();
            distanceToTarget = MathUtil.horizontalDistance(botLoc, moveTarget);
        } else if (movingForward) {
            distanceToTarget = SPRINT_JUMP_MIN_DISTANCE + 1;
        }

        if (distanceToTarget < SPRINT_JUMP_MIN_DISTANCE) {
            inSprintJumpCycle = false;
            if (distanceToTarget > 2.0 && !sneaking) {
                sprintController.startSprinting();
            }
            return;
        }

        inSprintJumpCycle = true;
        sprintController.startSprinting();

        DifficultyProfile diff = bot.getDifficultyProfile();
        double diffFraction = diff.getDifficulty().asFraction();

        int optimalJumpInterval = 12;
        int jumpTimingVariance = (int) Math.round(3.0 * (1.0 - diffFraction));
        int currentJumpInterval = optimalJumpInterval + RandomUtil.nextInt(-jumpTimingVariance,
                jumpTimingVariance);
        currentJumpInterval = Math.max(8, currentJumpInterval);

        boolean onGround = NMSHelper.isOnGround(entity);

        if (onGround && ticksSinceLastSprintJump >= currentJumpInterval) {
            double jumpChance = MathUtil.lerp(0.6, 1.0, diffFraction);
            if (RandomUtil.chance(jumpChance)) {
                jumpController.jump();
                ticksSinceLastSprintJump = 0;
            } else {
                ticksSinceLastSprintJump = currentJumpInterval - 2;
            }
        }
    }

    // ─── Look Direction ─────────────────────────────────────────

    private void updateLookDirection(@Nonnull LivingEntity entity) {
        DifficultyProfile diff = bot.getDifficultyProfile();
        double aimSpeed = diff.getAimSpeedDegPerTick();
        double headNoise = diff.getHeadMovementNoise();

        if (lookTarget != null) {
            Location eyePos = entity.getEyeLocation();
            float targetYaw = MathUtil.calculateYaw(eyePos, lookTarget);
            float targetPitch = MathUtil.calculatePitch(eyePos, lookTarget);

            double yawDiff = MathUtil.angleDifference(currentYaw, targetYaw);
            double pitchDiff = targetPitch - currentPitch;

            double maxRotation = aimSpeed;
            if (Math.abs(yawDiff) > maxRotation) {
                yawDiff = Math.signum(yawDiff) * maxRotation;
            }
            if (Math.abs(pitchDiff) > maxRotation * 0.5) {
                pitchDiff = Math.signum(pitchDiff) * maxRotation * 0.5;
            }

            currentYaw = (float) MathUtil.normalizeAngle(currentYaw + yawDiff);
            currentPitch = (float) MathUtil.clamp(currentPitch + pitchDiff, -90.0, 90.0);
        } else {
            currentYaw += (float) RandomUtil.gaussian(0.0, headNoise * 2.0);
            currentPitch += (float) RandomUtil.gaussian(0.0, headNoise);
            currentPitch = (float) MathUtil.clamp(currentPitch, -90.0, 90.0);
            currentYaw = (float) MathUtil.normalizeAngle(currentYaw);
        }
    }

    private void applyLookToEntity(@Nonnull LivingEntity entity) {
        Location loc = entity.getLocation();
        loc.setYaw(currentYaw);
        loc.setPitch(currentPitch);
        entity.teleport(loc);
        PacketUtil.sendHeadRotation(entity, currentYaw);
        PacketUtil.sendEntityLook(entity, currentYaw, currentPitch);
    }

    // ─── Movement ───────────────────────────────────────────────

    /**
     * Calculates and applies per-tick movement toward the current target.
     *
     * [FIX] Strafe velocity is only added when the bot is in melee range
     * (has a moveTarget within 4 blocks) and strafing is active. Previously
     * strafe was added unconditionally on top of directional movement,
     * causing overshoot.
     */
    private void applyMovement(@Nonnull LivingEntity entity) {
        Location currentLoc = entity.getLocation();
        DifficultyProfile diff = bot.getDifficultyProfile();

        Vector moveDirection;
        if (moveTarget != null) {
            double horizDist = MathUtil.horizontalDistance(currentLoc, moveTarget);
            if (horizDist < 0.5) {
                moveTarget = null;
                inSprintJumpCycle = false;
                humanMotion.applyDeceleration(entity);
                return;
            }
            moveDirection = MathUtil.directionTo(currentLoc, moveTarget);
        } else if (movingForward) {
            moveDirection = getForwardDirection();
        } else if (movingBackward) {
            moveDirection = getForwardDirection().multiply(-1);
        } else {
            humanMotion.applyDeceleration(entity);
            return;
        }

        double baseSpeed = getBaseMovementSpeed();
        if (sneaking) {
            baseSpeed *= 0.3;
        }
        if (sprintController.isSprinting()) {
            baseSpeed *= 1.3;
        }
        baseSpeed *= speedMultiplier;

        Vector velocity = humanMotion.processMovement(entity, moveDirection, baseSpeed);

        if (strafeController.isStrafing()) {
            boolean inMeleeRange = false;
            if (moveTarget != null) {
                double dist = MathUtil.horizontalDistance(currentLoc, moveTarget);
                inMeleeRange = dist <= 4.0;
            } else {
                inMeleeRange = true;
            }
            if (inMeleeRange) {
                Vector rightDir = getRightDirection();
                Vector strafeVelocity = strafeController.getStrafeVelocity(rightDir);
                velocity.add(strafeVelocity);
            }
        }

        velocity.setY(entity.getVelocity().getY());
        entity.setVelocity(velocity);
    }

    private void updateSneakState(@Nonnull LivingEntity entity) {
        NMSHelper.setSneaking(entity, sneaking);
    }

    // ─── Movement Commands ──────────────────────────────────────

    public void setMoveTarget(@Nullable Location target) {
        setMoveTarget(target, MovementAuthority.AI_GENERAL);
    }

    public boolean setMoveTarget(@Nullable Location target, @Nonnull MovementAuthority authority) {
        if (target == null) {
            if (authority.ordinal() >= currentAuthority.ordinal()) {
                this.moveTarget = null;
                this.movingForward = false;
                this.movingBackward = false;
                this.inSprintJumpCycle = false;
                return true;
            }
            return false;
        }
        if (authority.ordinal() >= currentAuthority.ordinal()) {
            this.moveTarget = target;
            this.currentAuthority = authority;
            this.movingForward = false;
            this.movingBackward = false;
            return true;
        }
        return false;
    }

    public boolean claimAuthority(@Nonnull MovementAuthority authority) {
        if (authority.ordinal() >= currentAuthority.ordinal()) {
            currentAuthority = authority;
            return true;
        }
        return false;
    }

    public void releaseAuthority(@Nonnull MovementAuthority authority) {
        if (authority.ordinal() >= currentAuthority.ordinal()) {
            currentAuthority = MovementAuthority.NONE;
        }
    }

    public void resetAuthority() {
        currentAuthority = MovementAuthority.NONE;
    }

    @Nonnull
    public MovementAuthority getCurrentAuthority() {
        return currentAuthority;
    }

    public void setMovingForward(boolean forward) {
        setMovingForward(forward, MovementAuthority.AI_GENERAL);
    }

    public boolean setMovingForward(boolean forward, @Nonnull MovementAuthority authority) {
        if (authority.ordinal() < currentAuthority.ordinal()) {
            return false;
        }
        this.movingForward = forward;
        if (forward) {
            this.currentAuthority = authority;
            this.moveTarget = null;
            this.movingBackward = false;
        }
        return true;
    }

    public void setMovingBackward(boolean backward) {
        setMovingBackward(backward, MovementAuthority.AI_GENERAL);
    }

    public boolean setMovingBackward(boolean backward, @Nonnull MovementAuthority authority) {
        if (authority.ordinal() < currentAuthority.ordinal()) {
            return false;
        }
        this.movingBackward = backward;
        if (backward) {
            this.currentAuthority = authority;
            this.moveTarget = null;
            this.movingForward = false;
            this.inSprintJumpCycle = false;
        }
        return true;
    }

    public void setLookTarget(@Nullable Location target) {
        this.lookTarget = target;
    }

    public void setSneaking(boolean sneaking) {
        this.sneaking = sneaking;
        if (sneaking) {
            this.inSprintJumpCycle = false;
        }
    }

    public void setFrozen(boolean frozen) {
        this.frozen = frozen;
    }

    public void setSpeedMultiplier(double multiplier) {
        this.speedMultiplier = MathUtil.clamp(multiplier, 0.0, 3.0);
    }

    public void setSprintJumpEnabled(boolean enabled) {
        this.sprintJumpEnabled = enabled;
        if (!enabled) {
            this.inSprintJumpCycle = false;
        }
    }

    public void stopAll() {
        moveTarget = null;
        movingForward = false;
        movingBackward = false;
        inSprintJumpCycle = false;
        LivingEntity entity = bot.getLivingEntity();
        if (entity != null) {
            humanMotion.applyDeceleration(entity);
        }
    }

    public void sprintJump() {
        sprintController.startSprinting();
        jumpController.jump();
    }

    public void sprintJumpTo(@Nonnull Location target) {
        setSprintJumpEnabled(true);
        setMoveTarget(target);
    }

    // ─── Queries ────────────────────────────────────────────────

    @Nonnull
    public Vector getForwardDirection() {
        double yawRad = Math.toRadians(currentYaw);
        return new Vector(-Math.sin(yawRad), 0, Math.cos(yawRad)).normalize();
    }

    @Nonnull
    public Vector getRightDirection() {
        double yawRad = Math.toRadians(currentYaw - 90.0);
        return new Vector(-Math.sin(yawRad), 0, Math.cos(yawRad)).normalize();
    }

    private double getBaseMovementSpeed() {
        return 0.1;
    }

    public float getCurrentYaw() { return currentYaw; }
    public float getCurrentPitch() { return currentPitch; }
    public boolean isSneaking() { return sneaking; }
    public boolean isFrozen() { return frozen; }
    public boolean isSprintJumping() { return inSprintJumpCycle; }
    public boolean isSprintJumpEnabled() { return sprintJumpEnabled; }
    @Nonnull public SprintController getSprintController() { return sprintController; }
    @Nonnull public JumpController getJumpController() { return jumpController; }
    @Nonnull public StrafeController getStrafeController() { return strafeController; }
    @Nonnull public HumanMotionSimulator getHumanMotion() { return humanMotion; }
    @Nonnull public WaterMLGController getWaterMLGController() { return waterMLGController; }
    public boolean isMoving() { return moveTarget != null || movingForward || movingBackward; }
    @Nullable public Location getMoveTarget() { return moveTarget; }
    @Nullable public Location getLookTarget() { return lookTarget; }

    public void setCurrentYaw(float yaw) {
        this.currentYaw = (float) MathUtil.normalizeAngle(yaw);
    }

    public void setCurrentPitch(float pitch) {
        this.currentPitch = (float) MathUtil.clamp(pitch, -90.0, 90.0);
    }

    public enum MovementAuthority {
        NONE,
        AI_GENERAL,
        LOOT,
        HUNTING,
        COMBAT,
        BRIDGE,
        DEFENSE,
        FLEE
    }
}
