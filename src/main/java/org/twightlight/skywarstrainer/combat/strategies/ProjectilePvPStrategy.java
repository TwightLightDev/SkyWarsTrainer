package org.twightlight.skywarstrainer.combat.strategies;

import org.bukkit.Material;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.twightlight.skywarstrainer.bot.TrainerBot;
import org.twightlight.skywarstrainer.combat.ProjectileHandler;
import org.twightlight.skywarstrainer.config.DifficultyConfig.DifficultyProfile;
import org.twightlight.skywarstrainer.movement.MovementController;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * Projectile PvP strategy: Uses snowballs, eggs, bows, and other projectiles
 * during combat for ranged damage, knockback, and tactical advantage.
 *
 * <p>Key behaviors:
 * <ul>
 *   <li>Snowball/egg spam during approach to get first KB on the enemy</li>
 *   <li>Bow charge → release at optimal charge for damage vs. speed tradeoff</li>
 *   <li>Bow spam (quick release for KB, not damage) to keep the enemy back</li>
 *   <li>Snowball/egg on enemies who are on a bridge — void kill opportunity</li>
 *   <li>Priority targeting: checks if enemy is near a void edge → use projectile
 *       for knockback toward the void</li>
 * </ul></p>
 *
 * <p>This strategy is most effective at ranges beyond melee (4-25 blocks). It
 * deactivates when the enemy closes to melee range, deferring to strafe/W-tap
 * strategies. The {@code projectileAccuracy} difficulty parameter scales hit
 * chance and predictive aim quality.</p>
 */
public class ProjectilePvPStrategy implements CombatStrategy {

    /**
     * Operational sub-modes within the projectile strategy.
     */
    private enum Mode {
        /** Spam snowballs/eggs during approach for first knockback. */
        APPROACH_SPAM,
        /** Fully charged bow shot for damage. */
        BOW_POWER_SHOT,
        /** Quick bow shots for knockback pressure (not full charge). */
        BOW_SPAM,
        /** Target is on/near a bridge — focus KB projectiles for void kill. */
        BRIDGE_SNIPE,
        /** Target is near a void edge — use projectiles to push them off. */
        VOID_EDGE_PUSH,
        /** No suitable projectile mode — idle. */
        IDLE
    }

    /** Current operating mode. */
    private Mode currentMode;

    /** Ticks spent in the current mode without a mode change. */
    private int modeTicks;

    /** Cooldown between mode re-evaluations to prevent rapid flip-flopping. */
    private int modeEvalCooldown;

    /** Number of projectiles thrown in current engagement for resource tracking. */
    private int projectilesUsed;

    /** Maximum ticks to stay in a single mode before re-evaluating. */
    private static final int MAX_MODE_TICKS = 60;

    /** Ticks between mode re-evaluations. */
    private static final int MODE_EVAL_INTERVAL = 10;

    @Nonnull
    @Override
    public String getName() {
        return "ProjectilePvP";
    }

    /**
     * {@inheritDoc}
     *
     * <p>Activates when:
     * <ul>
     *   <li>The bot has any ranged weapon (bow+arrows, snowball, egg)</li>
     *   <li>The target is beyond melee range (>4 blocks) OR near a void edge</li>
     *   <li>The bot's projectile accuracy is above minimum threshold (0.1)</li>
     * </ul></p>
     */
    @Override
    public boolean shouldActivate(@Nonnull TrainerBot bot) {
        DifficultyProfile diff = bot.getDifficultyProfile();
        if (diff.getProjectileAccuracy() < 0.1) return false;

        Player player = bot.getPlayerEntity();
        if (player == null) return false;

        // Must have some ranged capability
        boolean hasBow = player.getInventory().contains(Material.BOW)
                && player.getInventory().contains(Material.ARROW);
        boolean hasThrowables = player.getInventory().contains(Material.SNOW_BALL)
                || player.getInventory().contains(Material.EGG);

        if (!hasBow && !hasThrowables) return false;

        // Find the nearest enemy
        LivingEntity target = findNearestEnemy(bot);
        if (target == null) return false;

        LivingEntity botEntity = bot.getLivingEntity();
        if (botEntity == null) return false;

        double range = botEntity.getLocation().distance(target.getLocation());

        // Activate if target is beyond melee range
        if (range > 4.0) return true;

        // Also activate if target is near a void edge (KB projectiles are very valuable)
        if (isTargetNearVoid(bot, target)) return true;

        // Also activate if target is on a bridge (prime void-kill opportunity)
        if (isTargetOnBridge(bot, target)) return true;

        return false;
    }

    /**
     * {@inheritDoc}
     *
     * <p>Executes the current projectile mode. Each mode delegates to the
     * bot's {@link ProjectileHandler} for the actual projectile launch.
     * The strategy manages mode selection, timing, and resource awareness.</p>
     */
    @Override
    public void execute(@Nonnull TrainerBot bot) {
        LivingEntity botEntity = bot.getLivingEntity();
        Player player = bot.getPlayerEntity();
        if (botEntity == null || player == null) return;

        DifficultyProfile diff = bot.getDifficultyProfile();
        ProjectileHandler projectileHandler = bot.getCombatEngine().getProjectileHandler();

        // Tick the projectile handler's cooldowns
        projectileHandler.tick();

        modeTicks++;
        modeEvalCooldown--;

        // Re-evaluate mode periodically or when current mode has run too long
        if (modeEvalCooldown <= 0 || modeTicks >= MAX_MODE_TICKS) {
            currentMode = evaluateBestMode(bot, player, diff);
            modeTicks = 0;
            modeEvalCooldown = MODE_EVAL_INTERVAL;
        }

        // Execute the current mode
        switch (currentMode) {
            case APPROACH_SPAM:
                executeApproachSpam(bot, player, projectileHandler);
                break;

            case BOW_POWER_SHOT:
                executeBowPowerShot(bot, player, projectileHandler);
                break;

            case BOW_SPAM:
                executeBowSpam(bot, player, projectileHandler);
                break;

            case BRIDGE_SNIPE:
                executeBridgeSnipe(bot, player, projectileHandler, diff);
                break;

            case VOID_EDGE_PUSH:
                executeVoidEdgePush(bot, player, projectileHandler, diff);
                break;

            case IDLE:
            default:
                // Nothing to do — defer to other strategies
                break;
        }
    }

    /**
     * Evaluates and returns the best projectile mode for the current situation.
     *
     * @param bot    the bot
     * @param player the bot's player entity
     * @param diff   the difficulty profile
     * @return the best mode to operate in
     */
    @Nonnull
    private Mode evaluateBestMode(@Nonnull TrainerBot bot, @Nonnull Player player,
                                  @Nonnull DifficultyProfile diff) {
        LivingEntity target = findNearestEnemy(bot);
        if (target == null) return Mode.IDLE;

        LivingEntity botEntity = bot.getLivingEntity();
        if (botEntity == null) return Mode.IDLE;

        double range = botEntity.getLocation().distance(target.getLocation());

        // Priority 1: Void edge push — if target is near void, this is a kill opportunity
        if (isTargetNearVoid(bot, target) && hasAnyThrowable(player)) {
            return Mode.VOID_EDGE_PUSH;
        }

        // Priority 2: Bridge snipe — if target is on a bridge, knock them off
        if (isTargetOnBridge(bot, target) && hasAnyThrowable(player)) {
            return Mode.BRIDGE_SNIPE;
        }

        // Priority 3: Bow power shot at long range
        boolean hasBow = player.getInventory().contains(Material.BOW)
                && player.getInventory().contains(Material.ARROW);
        if (hasBow && range > 15.0 && diff.getProjectileAccuracy() >= 0.4) {
            return Mode.BOW_POWER_SHOT;
        }

        // Priority 4: Bow spam at medium range for pressure
        if (hasBow && range > 8.0 && range <= 15.0) {
            return Mode.BOW_SPAM;
        }

        // Priority 5: Approach spam with throwables at close-medium range
        if (hasAnyThrowable(player) && range > 4.0 && range <= 12.0) {
            return Mode.APPROACH_SPAM;
        }

        // Priority 6: Bow power shot as fallback if bow is available
        if (hasBow && range > 4.0) {
            return Mode.BOW_POWER_SHOT;
        }

        return Mode.IDLE;
    }

    /**
     * Spams snowballs/eggs while approaching the enemy for first knockback.
     * This disrupts the enemy's movement and gives the bot initiative.
     */
    private void executeApproachSpam(@Nonnull TrainerBot bot, @Nonnull Player player,
                                     @Nonnull ProjectileHandler handler) {
        MovementController mc = bot.getMovementController();
        LivingEntity target = findNearestEnemy(bot);

        if (mc != null && target != null) {
            // [FIX] Use COMBAT authority
            mc.setMoveTarget(target.getLocation(), MovementController.MovementAuthority.COMBAT);
            mc.setLookTarget(target.getLocation().add(0, 1.0, 0));
            mc.getSprintController().startSprinting();
        }

        if (!handler.isProjectileReady()) return;

        if (player.getInventory().contains(Material.SNOW_BALL)) {
            if (handler.trySnowball()) {
                projectilesUsed++;
            }
        } else if (player.getInventory().contains(Material.EGG)) {
            if (handler.tryEgg()) {
                projectilesUsed++;
            }
        }
    }


    /**
     * Charges a bow fully and releases for maximum damage. Used at long range
     * where the travel time allows full charge.
     */
    private void executeBowPowerShot(@Nonnull TrainerBot bot, @Nonnull Player player,
                                     @Nonnull ProjectileHandler handler) {
        // Maintain distance — don't sprint toward the enemy
        MovementController mc = bot.getMovementController();
        LivingEntity target = findNearestEnemy(bot);

        if (mc != null && target != null) {
            // Strafe while shooting for harder-to-hit positioning
            mc.setLookTarget(target.getLocation().add(0, 1.0, 0));
            mc.getStrafeController().startStrafing();
            mc.getStrafeController().tick();
        }

        // Attempt the bow shot (handler manages draw → charge → release cycle)
        if (handler.tryBowShot()) {
            projectilesUsed++;
        }
    }

    /**
     * Quick-release bow shots for knockback pressure rather than damage.
     * The bow is charged minimally (5-10 ticks) to send arrows quickly,
     * keeping the enemy pushed back and disrupted.
     */
    private void executeBowSpam(@Nonnull TrainerBot bot, @Nonnull Player player,
                                @Nonnull ProjectileHandler handler) {
        MovementController mc = bot.getMovementController();
        LivingEntity target = findNearestEnemy(bot);

        if (mc != null && target != null) {
            mc.setLookTarget(target.getLocation().add(0, 1.0, 0));
            // Strafe to avoid return fire
            mc.getStrafeController().startStrafing();
            mc.getStrafeController().tick();
        }

        // The handler's tryBowShot() handles the charge cycle.
        // For bow spam, we want shorter charge times — the handler already
        // scales charge time based on projectileAccuracy. At lower accuracy,
        // it releases sooner (which coincidentally is the bow-spam behavior).
        if (handler.tryBowShot()) {
            projectilesUsed++;
        }
    }

    /**
     * Targets an enemy who is on a bridge with knockback projectiles.
     * A hit while they're bridging over void = instant death for them.
     */
    private void executeBridgeSnipe(@Nonnull TrainerBot bot, @Nonnull Player player,
                                    @Nonnull ProjectileHandler handler,
                                    @Nonnull DifficultyProfile diff) {
        LivingEntity target = findNearestEnemy(bot);
        if (target == null) return;

        MovementController mc = bot.getMovementController();
        if (mc != null) {
            mc.setLookTarget(target.getLocation().add(0, 1.0, 0));
        }

        // Prioritize throwables for immediate KB (no charge time like bow)
        if (!handler.isProjectileReady()) return;

        if (player.getInventory().contains(Material.SNOW_BALL)) {
            if (handler.trySnowball()) {
                projectilesUsed++;
            }
        } else if (player.getInventory().contains(Material.EGG)) {
            if (handler.tryEgg()) {
                projectilesUsed++;
            }
        } else {
            // Fall back to bow for the knockback
            if (handler.tryBowShot()) {
                projectilesUsed++;
            }
        }
    }

    /**
     * Targets an enemy who is near a void edge with knockback projectiles
     * to push them off the map.
     */
    private void executeVoidEdgePush(@Nonnull TrainerBot bot, @Nonnull Player player,
                                     @Nonnull ProjectileHandler handler,
                                     @Nonnull DifficultyProfile diff) {
        // Identical behavior to bridge snipe — the key difference is
        // the mode SELECTION (void edge detected vs bridge detected).
        // The execution is the same: throw KB projectiles at the target.
        executeBridgeSnipe(bot, player, handler, diff);
    }

    @Override
    public double getPriority(@Nonnull TrainerBot bot) {
        DifficultyProfile diff = bot.getDifficultyProfile();
        double basePriority = 5.0 * diff.getProjectileAccuracy();

        LivingEntity target = findNearestEnemy(bot);
        if (target == null) return 0.0;

        LivingEntity botEntity = bot.getLivingEntity();
        if (botEntity == null) return 0.0;

        double range = botEntity.getLocation().distance(target.getLocation());

        // Higher priority at longer ranges (projectiles are the only option)
        if (range > 15.0) {
            basePriority += 4.0;
        } else if (range > 8.0) {
            basePriority += 2.0;
        }

        // Much higher priority when target is near void or on bridge
        if (isTargetNearVoid(bot, target)) {
            basePriority += 6.0; // Void kill opportunity is extremely valuable
        }
        if (isTargetOnBridge(bot, target)) {
            basePriority += 5.0;
        }

        // Reduce priority if running low on projectiles
        Player player = bot.getPlayerEntity();
        if (player != null) {
            int totalProjectiles = countProjectiles(player);
            if (totalProjectiles <= 3) {
                basePriority *= 0.5; // Conserve limited ammo
            }
        }

        return basePriority;
    }

    @Override
    public void reset() {
        currentMode = Mode.IDLE;
        modeTicks = 0;
        modeEvalCooldown = 0;
        projectilesUsed = 0;
    }

    // ─── Helper Methods ─────────────────────────────────────────

    /**
     * Checks whether the target is near a void edge by examining the blocks
     * around them. A target near void is a prime knockback opportunity.
     *
     * @param bot    the bot
     * @param target the target entity
     * @return true if the target appears to be near a void edge
     */
    private boolean isTargetNearVoid(@Nonnull TrainerBot bot, @Nonnull LivingEntity target) {
        org.bukkit.Location targetLoc = target.getLocation();
        org.bukkit.World world = targetLoc.getWorld();
        if (world == null) return false;

        // Check blocks in 4 cardinal directions for void (air below)
        int[][] directions = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}};
        int voidDirections = 0;

        for (int[] dir : directions) {
            org.bukkit.Location checkLoc = targetLoc.clone().add(dir[0] * 2, 0, dir[1] * 2);
            // Check if there's void below (no solid block for 10+ blocks down)
            boolean hasGround = false;
            for (int y = 0; y >= -10; y--) {
                org.bukkit.block.Block block = checkLoc.clone().add(0, y, 0).getBlock();
                if (block.getType().isSolid()) {
                    hasGround = true;
                    break;
                }
            }
            if (!hasGround) {
                voidDirections++;
            }
        }

        // Target is "near void" if at least 2 directions have void
        return voidDirections >= 2;
    }

    /**
     * Checks whether the target appears to be on a narrow bridge.
     * A bridge is detected by checking if the target is on a 1-2 block wide
     * platform with void on both sides.
     *
     * @param bot    the bot
     * @param target the target entity
     * @return true if the target appears to be on a bridge
     */
    private boolean isTargetOnBridge(@Nonnull TrainerBot bot, @Nonnull LivingEntity target) {
        org.bukkit.Location targetLoc = target.getLocation();
        org.bukkit.World world = targetLoc.getWorld();
        if (world == null) return false;

        // Check if standing on a narrow platform (bridge indicator)
        org.bukkit.block.Block below = targetLoc.clone().add(0, -1, 0).getBlock();
        if (!below.getType().isSolid()) return false;

        // Check perpendicular directions for void
        // First check X-axis
        boolean voidPosX = !hasSolidBelow(targetLoc.clone().add(2, 0, 0));
        boolean voidNegX = !hasSolidBelow(targetLoc.clone().add(-2, 0, 0));
        if (voidPosX && voidNegX) return true;

        // Then check Z-axis
        boolean voidPosZ = !hasSolidBelow(targetLoc.clone().add(0, 0, 2));
        boolean voidNegZ = !hasSolidBelow(targetLoc.clone().add(0, 0, -2));
        return voidPosZ && voidNegZ;
    }

    /**
     * Checks if there is solid ground below a location within 10 blocks.
     *
     * @param location the location to check
     * @return true if solid ground exists below
     */
    private boolean hasSolidBelow(@Nonnull org.bukkit.Location location) {
        for (int y = 0; y >= -10; y--) {
            org.bukkit.block.Block block = location.clone().add(0, y, 0).getBlock();
            if (block.getType().isSolid()) return true;
        }
        return false;
    }

    /**
     * Checks if the player has any throwable projectile (snowball or egg).
     *
     * @param player the player
     * @return true if throwables are available
     */
    private boolean hasAnyThrowable(@Nonnull Player player) {
        return player.getInventory().contains(Material.SNOW_BALL)
                || player.getInventory().contains(Material.EGG);
    }

    /**
     * Counts total available projectiles (arrows + snowballs + eggs).
     *
     * @param player the player
     * @return total projectile count
     */
    private int countProjectiles(@Nonnull Player player) {
        int count = 0;
        for (ItemStack stack : player.getInventory().getContents()) {
            if (stack == null) continue;
            Material type = stack.getType();
            if (type == Material.ARROW || type == Material.SNOW_BALL || type == Material.EGG) {
                count += stack.getAmount();
            }
        }
        return count;
    }

    /**
     * Finds the nearest visible enemy player to the bot.
     *
     * @param bot the bot
     * @return the nearest enemy, or null if none visible
     */
    @Nullable
    private LivingEntity findNearestEnemy(@Nonnull TrainerBot bot) {
        LivingEntity botEntity = bot.getLivingEntity();
        if (botEntity == null) return null;

        double nearestDist = Double.MAX_VALUE;
        LivingEntity nearest = null;
        double awarenessRadius = bot.getDifficultyProfile().getAwarenessRadius();

        for (org.bukkit.entity.Entity entity : botEntity.getNearbyEntities(
                awarenessRadius, awarenessRadius, awarenessRadius)) {
            if (entity instanceof Player && !entity.isDead()
                    && !entity.getUniqueId().equals(botEntity.getUniqueId())) {
                double dist = botEntity.getLocation().distanceSquared(entity.getLocation());
                if (dist < nearestDist) {
                    nearestDist = dist;
                    nearest = (LivingEntity) entity;
                }
            }
        }
        return nearest;
    }
}
