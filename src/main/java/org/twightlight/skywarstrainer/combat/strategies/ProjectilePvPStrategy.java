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

import org.twightlight.skywarstrainer.combat.CombatUtils;

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
        LivingEntity target = CombatUtils.findNearestEnemy(bot);
        if (target == null) return false;

        LivingEntity botEntity = bot.getLivingEntity();
        if (botEntity == null) return false;

        double range = botEntity.getLocation().distance(target.getLocation());

        // Activate if target is beyond melee range
        if (range > 4.0) return true;

        // Also activate if target is near a void edge (KB projectiles are very valuable)
        if (CombatUtils.isTargetNearVoid(target)) return true;

        // Also activate if target is on a bridge (prime void-kill opportunity)
        if (CombatUtils.isTargetOnBridge(target)) return true;

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

        modeTicks++;
        modeEvalCooldown--;

        if (modeEvalCooldown <= 0 || modeTicks >= MAX_MODE_TICKS) {
            currentMode = evaluateBestMode(bot, player, diff);
            modeTicks = 0;
            modeEvalCooldown = MODE_EVAL_INTERVAL;
        }

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
        LivingEntity target = CombatUtils.findNearestEnemy(bot);
        if (target == null) return Mode.IDLE;

        LivingEntity botEntity = bot.getLivingEntity();
        if (botEntity == null) return Mode.IDLE;

        double range = botEntity.getLocation().distance(target.getLocation());

        // Priority 1: Void edge push — if target is near void, this is a kill opportunity
        if (CombatUtils.isTargetNearVoid(target) && CombatUtils.hasAnyThrowable(player)) {
            return Mode.VOID_EDGE_PUSH;
        }

        // Priority 2: Bridge snipe — if target is on a bridge, knock them off
        if (CombatUtils.isTargetOnBridge(target) && CombatUtils.hasAnyThrowable(player)) {
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
        if (CombatUtils.hasAnyThrowable(player) && range > 4.0 && range <= 12.0) {
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
        LivingEntity target = CombatUtils.findNearestEnemy(bot);

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
        LivingEntity target = CombatUtils.findNearestEnemy(bot);

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
        LivingEntity target = CombatUtils.findNearestEnemy(bot);

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
        LivingEntity target = CombatUtils.findNearestEnemy(bot);
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

        LivingEntity target = CombatUtils.findNearestEnemy(bot);
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
        if (CombatUtils.isTargetNearVoid(target)) {
            basePriority += 6.0; // Void kill opportunity is extremely valuable
        }
        if (CombatUtils.isTargetOnBridge(target)) {
            basePriority += 5.0;
        }

        // Reduce priority if running low on projectiles
        Player player = bot.getPlayerEntity();
        if (player != null) {
            int totalProjectiles = CombatUtils.countProjectiles(player);
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
}
