package org.twightlight.skywarstrainer.movement;

import org.bukkit.Location;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
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
 * Central movement controller for a trainer bot. All movement actions flow through
 * this controller, which delegates to specialized sub-controllers (sprint, jump,
 * strafe) and applies human motion simulation for realism.
 *
 * <p>This controller is ticked every server tick (20 TPS) because movement must
 * be smooth. It manages the bot's velocity, look direction, sneaking state, and
 * coordinates between sub-controllers.</p>
 *
 * <p>Movement commands are issued by higher-level systems (behavior tree, combat
 * engine, bridge engine) as target positions or directional intents. The controller
 * translates these into per-tick position updates with noise injection.</p>
 *
 * <h3>Sprint-Jump Travel:</h3>
 * <p>When the bot has a move target more than {@value #SPRINT_JUMP_MIN_DISTANCE} blocks
 * away, the controller automatically engages sprint-jump travel — the standard fastest
 * travel method in Minecraft 1.8. This is done by combining sprinting with periodic
 * jumps timed to maximize forward momentum. Sprint-jumping provides approximately a
 * 40% speed boost over regular sprinting and is what real players do constantly when
 * traveling any meaningful distance.</p>
 *
 * <p>The sprint-jump behavior is skill-aware: higher difficulty bots time their jumps
 * more optimally (jumping right as they land for maximum momentum chain), while lower
 * difficulty bots have less precise jump timing and may occasionally forget to jump.</p>
 */
public class MovementController {

    private final TrainerBot bot;
    private final SprintController sprintController;
    private final JumpController jumpController;
    private final StrafeController strafeController;
    private final HumanMotionSimulator humanMotion;
    private final WaterMLGController waterMLGController;

    /** The location the bot is trying to move toward. Null if no movement target. */
    private Location moveTarget;

    /** Whether the bot is currently trying to move forward. */
    private boolean movingForward;

    /** Whether the bot is currently trying to move backward. */
    private boolean movingBackward;

    /** Whether the bot should be sneaking. */
    private boolean sneaking;

    /** Whether the bot should be looking at a specific target. */
    private Location lookTarget;

    /** Current desired yaw (degrees) after smoothing. */
    private float currentYaw;

    /** Current desired pitch (degrees) after smoothing. */
    private float currentPitch;

    /** Whether movement is frozen (e.g., while looting a chest). */
    private boolean frozen;

    /** Movement speed multiplier applied on top of base speed. */
    private double speedMultiplier;

    /**
     * Whether sprint-jump travel mode is enabled. When enabled, the controller
     * will automatically sprint and jump periodically when moving toward a target
     * that is far enough away. This can be disabled (e.g., during bridging or sneaking).
     */
    private boolean sprintJumpEnabled;

    /**
     * Minimum distance (in blocks) to the move target before sprint-jump travel
     * activates. Below this distance, the bot walks or sprints normally to avoid
     * overshooting.
     */
    private static final double SPRINT_JUMP_MIN_DISTANCE = 5.0;

    /**
     * Ticks since the last sprint-jump. Used to time jumps optimally — in vanilla 1.8,
     * a sprint-jump cycle is approximately 12 ticks (land → immediately jump again).
     */
    private int ticksSinceLastSprintJump;

    /**
     * Whether the bot is currently in a sprint-jump travel cycle.
     * Tracked separately from just "sprinting + jumping" because we need to
     * manage the full chain: sprint → jump → land → jump again.
     */
    private boolean inSprintJumpCycle;

    /**
     * Creates a new MovementController for the given bot.
     *
     * @param bot the owning trainer bot
     */
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

        // Initialize yaw/pitch from current entity orientation
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
     * <p>Processing order:
     * <ol>
     *   <li>Update look direction (smooth aim toward target or idle noise).</li>
     *   <li>Process sprint state.</li>
     *   <li>Process jump logic.</li>
     *   <li>Calculate movement velocity toward target.</li>
     *   <li>Manage sprint-jump travel if applicable.</li>
     *   <li>Apply human motion noise.</li>
     *   <li>Apply sneaking state.</li>
     *   <li>Check for fall damage / water MLG.</li>
     * </ol></p>
     */
    public void tick() {
        LivingEntity entity = bot.getLivingEntity();
        if (entity == null || entity.isDead()) return;

        // Update sub-controllers
        sprintController.tick();
        jumpController.tick();
        waterMLGController.tick();

        // Update look direction
        updateLookDirection(entity);

        if (frozen) {
            // When frozen, still update look but don't move
            applyLookToEntity(entity);
            return;
        }

        // Calculate and apply movement
        if (moveTarget != null || movingForward || movingBackward) {
            // Check if sprint-jump travel should be active
            updateSprintJumpTravel(entity);
            applyMovement(entity);
        } else {
            // No movement target — stop sprint-jump cycle and decelerate
            inSprintJumpCycle = false;
            humanMotion.applyDeceleration(entity);
        }

        // Apply sneaking state
        updateSneakState(entity);

        // Apply the final look angles to the entity
        applyLookToEntity(entity);
    }

    // ─── Sprint-Jump Travel ─────────────────────────────────────

    /**
     * Manages the sprint-jump travel cycle. When moving toward a distant target,
     * the bot sprints and jumps continuously for maximum speed — the standard
     * fastest travel method in Minecraft 1.8.
     *
     * <p>Sprint-jumping works by chaining sprint → jump → land → immediately jump
     * again. Each cycle covers about 5-6 blocks and takes roughly 12 ticks. The
     * speed boost from sprint-jumping is significant (~5.6 blocks/sec vs ~4.3
     * blocks/sec walking).</p>
     *
     * <p>The system considers:
     * <ul>
     *   <li>Distance to target (must be > {@value #SPRINT_JUMP_MIN_DISTANCE} blocks)</li>
     *   <li>Sneaking state (no sprint-jumping while sneaking)</li>
     *   <li>Sprint-jump enabled flag (can be disabled by bridge/combat systems)</li>
     *   <li>Difficulty: higher difficulty = more consistent sprint-jump timing</li>
     * </ul></p>
     *
     * @param entity the bot's living entity
     */
    private void updateSprintJumpTravel(@Nonnull LivingEntity entity) {
        ticksSinceLastSprintJump++;

        // Check if sprint-jump conditions are met
        if (!sprintJumpEnabled || sneaking || movingBackward) {
            inSprintJumpCycle = false;
            return;
        }

        double distanceToTarget = 0;
        if (moveTarget != null) {
            Location botLoc = entity.getLocation();
            distanceToTarget = MathUtil.horizontalDistance(botLoc, moveTarget);
        } else if (movingForward) {
            // When moving forward without a specific target, enable sprint-jump
            // if requested (e.g., running away)
            distanceToTarget = SPRINT_JUMP_MIN_DISTANCE + 1; // Always qualify
        }

        if (distanceToTarget < SPRINT_JUMP_MIN_DISTANCE) {
            // Too close to target — walk/sprint normally to avoid overshooting
            inSprintJumpCycle = false;
            // Still sprint for the last stretch if not too close
            if (distanceToTarget > 2.0 && !sneaking) {
                sprintController.startSprinting();
            }
            return;
        }

        // Activate sprint-jump cycle
        inSprintJumpCycle = true;
        sprintController.startSprinting();

        // Determine optimal jump timing based on difficulty
        DifficultyProfile diff = bot.getDifficultyProfile();
        double diffFraction = diff.getDifficulty().asFraction();

        // Optimal jump interval: ~12 ticks (when the bot lands from previous jump).
        // Lower difficulty bots have slightly longer intervals and may miss some jumps.
        int optimalJumpInterval = 12;
        int jumpTimingVariance = (int) Math.round(3.0 * (1.0 - diffFraction)); // ±0-3 ticks
        int currentJumpInterval = optimalJumpInterval + RandomUtil.nextInt(-jumpTimingVariance,
                jumpTimingVariance);
        currentJumpInterval = Math.max(8, currentJumpInterval); // Minimum 8 ticks between jumps

        // Check if it's time to jump
        boolean onGround = NMSHelper.isOnGround(entity);

        if (onGround && ticksSinceLastSprintJump >= currentJumpInterval) {
            // Low difficulty bots sometimes forget to jump (miss the sprint-jump chain)
            double jumpChance = MathUtil.lerp(0.6, 1.0, diffFraction);
            if (RandomUtil.chance(jumpChance)) {
                jumpController.jump();
                ticksSinceLastSprintJump = 0;
            } else {
                // Missed jump — will try again next eligible tick
                // This creates the natural "sometimes runs, sometimes sprint-jumps"
                // pattern seen in lower-skill players
                ticksSinceLastSprintJump = currentJumpInterval - 2; // Try again soon
            }
        }
    }

    // ─── Look Direction ─────────────────────────────────────────

    /**
     * Updates the bot's current yaw and pitch, smoothly interpolating toward
     * the look target if one is set, or injecting idle head noise otherwise.
     *
     * @param entity the bot's living entity
     */
    private void updateLookDirection(@Nonnull LivingEntity entity) {
        DifficultyProfile diff = bot.getDifficultyProfile();
        double aimSpeed = diff.getAimSpeedDegPerTick();
        double headNoise = diff.getHeadMovementNoise();

        if (lookTarget != null) {
            // Calculate desired angles to look at target
            Location eyePos = entity.getEyeLocation();
            float targetYaw = MathUtil.calculateYaw(eyePos, lookTarget);
            float targetPitch = MathUtil.calculatePitch(eyePos, lookTarget);

            // Smooth interpolation with capped rotation speed
            double yawDiff = MathUtil.angleDifference(currentYaw, targetYaw);
            double pitchDiff = targetPitch - currentPitch;

            // Clamp rotation by aimSpeedDegPerTick
            double maxRotation = aimSpeed;
            if (Math.abs(yawDiff) > maxRotation) {
                yawDiff = Math.signum(yawDiff) * maxRotation;
            }
            if (Math.abs(pitchDiff) > maxRotation * 0.5) {
                // Pitch moves slower than yaw (realistic)
                pitchDiff = Math.signum(pitchDiff) * maxRotation * 0.5;
            }

            currentYaw = (float) MathUtil.normalizeAngle(currentYaw + yawDiff);
            currentPitch = (float) MathUtil.clamp(currentPitch + pitchDiff, -90.0, 90.0);
        } else {
            // No look target — add idle head noise for natural appearance
            currentYaw += (float) RandomUtil.gaussian(0.0, headNoise * 2.0);
            currentPitch += (float) RandomUtil.gaussian(0.0, headNoise);
            currentPitch = (float) MathUtil.clamp(currentPitch, -90.0, 90.0);
            currentYaw = (float) MathUtil.normalizeAngle(currentYaw);
        }
    }

    /**
     * Applies the current yaw and pitch to the bot's entity, sending packets
     * to nearby players for smooth visual rotation.
     *
     * @param entity the bot entity
     */
    private void applyLookToEntity(@Nonnull LivingEntity entity) {
        Location loc = entity.getLocation();
        loc.setYaw(currentYaw);
        loc.setPitch(currentPitch);

        /*
         * We teleport the entity to the same position with updated yaw/pitch.
         * This is how Citizens NPCs have their orientation updated.
         * Additionally, we send head rotation packets for smoother visual updates.
         */
        entity.teleport(loc);
        PacketUtil.sendHeadRotation(entity, currentYaw);
        PacketUtil.sendEntityLook(entity, currentYaw, currentPitch);
    }

    // ─── Movement ───────────────────────────────────────────────

    /**
     * Calculates and applies per-tick movement toward the current target.
     *
     * <p>When sprint-jump travel is active, the movement speed includes the sprint
     * multiplier (1.3x) and the jump provides additional forward momentum. The
     * combination results in roughly 5.6 blocks/sec — the fastest possible
     * horizontal speed in vanilla 1.8 without potions.</p>
     *
     * @param entity the bot entity
     */
    private void applyMovement(@Nonnull LivingEntity entity) {
        Location currentLoc = entity.getLocation();
        DifficultyProfile diff = bot.getDifficultyProfile();

        // Determine movement direction
        Vector moveDirection;
        if (moveTarget != null) {
            moveDirection = MathUtil.directionTo(currentLoc, moveTarget);
            // Check if we've reached the target (within 0.5 blocks)
            if (MathUtil.horizontalDistance(currentLoc, moveTarget) < 0.5) {
                moveTarget = null;
                inSprintJumpCycle = false;
                humanMotion.applyDeceleration(entity);
                return;
            }
        } else if (movingForward) {
            // Move in the direction the bot is currently facing
            moveDirection = getForwardDirection();
        } else if (movingBackward) {
            // Move opposite to facing direction
            moveDirection = getForwardDirection().multiply(-1);
        } else {
            return;
        }

        // Calculate base speed
        double baseSpeed = getBaseMovementSpeed();
        if (sneaking) {
            baseSpeed *= 0.3; // Sneaking speed multiplier (vanilla is ~0.3)
        }
        if (sprintController.isSprinting()) {
            baseSpeed *= 1.3; // Sprint speed multiplier (vanilla is ~1.3)
        }
        baseSpeed *= speedMultiplier;

        // Apply human motion simulation (noise, acceleration)
        Vector velocity = humanMotion.processMovement(entity, moveDirection, baseSpeed);

        // Apply the velocity
        velocity.setY(entity.getVelocity().getY()); // Preserve vertical velocity
        entity.setVelocity(velocity);
    }

    /**
     * Updates the sneaking state on the entity via NMS.
     *
     * @param entity the bot entity
     */
    private void updateSneakState(@Nonnull LivingEntity entity) {
        NMSHelper.setSneaking(entity, sneaking);
    }

    // ─── Movement Commands (called by higher-level systems) ─────

    /**
     * Sets a target location for the bot to walk/run toward.
     * The bot will move toward this point each tick until it arrives or
     * a new target is set. Set to null to stop moving.
     *
     * <p>If the target is more than {@value #SPRINT_JUMP_MIN_DISTANCE} blocks away
     * and sprint-jump is enabled, the bot will automatically sprint-jump for
     * maximum speed.</p>
     *
     * @param target the target location, or null to stop
     */
    public void setMoveTarget(@Nullable Location target) {
        this.moveTarget = target;
        if (target != null) {
            this.movingForward = false;
            this.movingBackward = false;
        } else {
            this.inSprintJumpCycle = false;
        }
    }

    /**
     * Sets the bot to move forward in its current facing direction.
     *
     * @param forward true to move forward, false to stop
     */
    public void setMovingForward(boolean forward) {
        this.movingForward = forward;
        if (forward) {
            this.moveTarget = null;
            this.movingBackward = false;
        }
    }

    /**
     * Sets the bot to move backward (away from its facing direction).
     *
     * @param backward true to move backward, false to stop
     */
    public void setMovingBackward(boolean backward) {
        this.movingBackward = backward;
        if (backward) {
            this.moveTarget = null;
            this.movingForward = false;
            this.inSprintJumpCycle = false; // No sprint-jumping while moving backward
        }
    }

    /**
     * Sets a location for the bot to continuously look at.
     * Set to null for idle head noise.
     *
     * @param target the look target, or null for idle
     */
    public void setLookTarget(@Nullable Location target) {
        this.lookTarget = target;
    }

    /**
     * Sets the sneaking state.
     *
     * @param sneaking whether the bot should sneak
     */
    public void setSneaking(boolean sneaking) {
        this.sneaking = sneaking;
        if (sneaking) {
            this.inSprintJumpCycle = false; // No sprint-jumping while sneaking
        }
    }

    /**
     * Freezes or unfreezes movement. While frozen, the bot still updates
     * its look direction but does not move.
     *
     * @param frozen true to freeze movement
     */
    public void setFrozen(boolean frozen) {
        this.frozen = frozen;
    }

    /**
     * Sets a movement speed multiplier on top of the base movement speed.
     *
     * @param multiplier the speed multiplier (1.0 = normal)
     */
    public void setSpeedMultiplier(double multiplier) {
        this.speedMultiplier = MathUtil.clamp(multiplier, 0.0, 3.0);
    }

    /**
     * Enables or disables automatic sprint-jump travel mode.
     *
     * <p>When enabled (default), the bot automatically sprint-jumps when moving
     * toward distant targets. Disable this during bridging, careful sneaking,
     * or other situations where jumping would be detrimental.</p>
     *
     * @param enabled true to enable sprint-jump travel
     */
    public void setSprintJumpEnabled(boolean enabled) {
        this.sprintJumpEnabled = enabled;
        if (!enabled) {
            this.inSprintJumpCycle = false;
        }
    }

    /**
     * Stops all movement immediately. Clears target, forward/backward flags,
     * disables sprint-jump cycle, and applies deceleration.
     */
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

    /**
     * Commands the bot to perform a single sprint-jump. This is the standard fastest
     * travel method in 1.8. Enables sprinting and queues a jump.
     *
     * <p>For continuous sprint-jump travel (which is what real players do), use
     * {@link #setSprintJumpEnabled(boolean)} + {@link #setMoveTarget(Location)} instead.
     * This method is for one-off sprint-jumps, e.g., closing distance in combat.</p>
     */
    public void sprintJump() {
        sprintController.startSprinting();
        jumpController.jump();
    }

    /**
     * Commands the bot to move toward a target using sprint-jump travel.
     * This is a convenience method that sets the target and ensures sprint-jump
     * is enabled.
     *
     * @param target the target location to sprint-jump toward
     */
    public void sprintJumpTo(@Nonnull Location target) {
        setSprintJumpEnabled(true);
        setMoveTarget(target);
    }

    // ─── Queries ────────────────────────────────────────────────

    /**
     * Returns the forward direction vector based on the bot's current yaw.
     * This is the horizontal direction the bot is facing.
     *
     * @return the forward direction unit vector (Y=0)
     */
    @Nonnull
    public Vector getForwardDirection() {
        double yawRad = Math.toRadians(currentYaw);
        return new Vector(-Math.sin(yawRad), 0, Math.cos(yawRad)).normalize();
    }

    /**
     * Returns the right-strafe direction vector (perpendicular to forward).
     *
     * @return the right direction unit vector (Y=0)
     */
    @Nonnull
    public Vector getRightDirection() {
        double yawRad = Math.toRadians(currentYaw - 90.0);
        return new Vector(-Math.sin(yawRad), 0, Math.cos(yawRad)).normalize();
    }

    /**
     * Returns the base walking speed in blocks per tick.
     * Vanilla walking speed is ~0.1 blocks/tick (4.317 blocks/sec at 20TPS).
     *
     * @return the base walking speed in blocks per tick
     */
    private double getBaseMovementSpeed() {
        return 0.1; // Vanilla walking speed
    }

    /** @return the current yaw in degrees */
    public float getCurrentYaw() { return currentYaw; }

    /** @return the current pitch in degrees */
    public float getCurrentPitch() { return currentPitch; }

    /** @return true if the bot is currently sneaking */
    public boolean isSneaking() { return sneaking; }

    /** @return true if movement is frozen */
    public boolean isFrozen() { return frozen; }

    /** @return true if the bot is currently in a sprint-jump travel cycle */
    public boolean isSprintJumping() { return inSprintJumpCycle; }

    /** @return true if sprint-jump travel mode is enabled */
    public boolean isSprintJumpEnabled() { return sprintJumpEnabled; }

    /** @return the sprint controller */
    @Nonnull
    public SprintController getSprintController() { return sprintController; }

    /** @return the jump controller */
    @Nonnull
    public JumpController getJumpController() { return jumpController; }

    /** @return the strafe controller */
    @Nonnull
    public StrafeController getStrafeController() { return strafeController; }

    /** @return the human motion simulator */
    @Nonnull
    public HumanMotionSimulator getHumanMotion() { return humanMotion; }

    /** @return the water MLG controller */
    @Nonnull
    public WaterMLGController getWaterMLGController() { return waterMLGController; }

    /** @return true if the bot is actively moving toward a target */
    public boolean isMoving() {
        return moveTarget != null || movingForward || movingBackward;
    }

    /** @return the current move target, or null if none */
    @Nullable
    public Location getMoveTarget() { return moveTarget; }

    /** @return the current look target, or null if none */
    @Nullable
    public Location getLookTarget() { return lookTarget; }

    /**
     * Sets the current yaw directly (used by combat aim controller to
     * override look direction with higher precision).
     *
     * @param yaw the new yaw in degrees
     */
    public void setCurrentYaw(float yaw) {
        this.currentYaw = (float) MathUtil.normalizeAngle(yaw);
    }

    /**
     * Sets the current pitch directly.
     *
     * @param pitch the new pitch in degrees [-90, 90]
     */
    public void setCurrentPitch(float pitch) {
        this.currentPitch = (float) MathUtil.clamp(pitch, -90.0, 90.0);
    }
}