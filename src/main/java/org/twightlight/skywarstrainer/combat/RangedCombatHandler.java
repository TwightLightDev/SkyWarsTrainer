package org.twightlight.skywarstrainer.combat;

import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.twightlight.skywarstrainer.bot.TrainerBot;
import org.twightlight.skywarstrainer.config.DifficultyConfig.DifficultyProfile;
import org.twightlight.skywarstrainer.inventory.UtilityItemHandler;
import org.twightlight.skywarstrainer.util.RandomUtil;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * Centralized ranged combat handler that decides which ranged/utility item
 * the bot should use on any given tick based on context, cooldowns, and priorities.
 *
 * <p>This handler bridges the gap between the {@link ProjectileHandler}
 * (which handles the mechanics of launching projectiles) and the
 * {@link UtilityItemHandler} (which handles utility item placement).
 * It provides a single entry point for all ranged actions:</p>
 *
 * <ul>
 *   <li>Bow (charged shot / spam)</li>
 *   <li>Snowball</li>
 *   <li>Egg</li>
 *   <li>Ender pearl (escape/reposition)</li>
 *   <li>Fishing rod (combo setup)</li>
 *   <li>TNT (area damage)</li>
 *   <li>Water bucket (push)</li>
 *   <li>Lava bucket (zone/damage)</li>
 *   <li>Flint & steel (ignite)</li>
 *   <li>Cobweb (slow/trap)</li>
 * </ul>
 *
 * <p>The handler evaluates the situation and returns the best ranged action
 * rather than trying them all sequentially. This avoids wasting cooldowns
 * on suboptimal items.</p>
 */
public class RangedCombatHandler {

    private final TrainerBot bot;
    private final ProjectileHandler projectileHandler;

    /**
     * Enumeration of all possible ranged/utility actions.
     */
    public enum RangedAction {
        BOW_POWER_SHOT,
        BOW_SPAM,
        SNOWBALL,
        EGG,
        FISHING_ROD,
        ENDER_PEARL,
        TNT,
        WATER_PUSH,
        LAVA_PLACE,
        FLINT_IGNITE,
        COBWEB_TRAP,
        COBWEB_RETREAT,
        NONE
    }

    public RangedCombatHandler(@Nonnull TrainerBot bot,
                               @Nonnull ProjectileHandler projectileHandler) {
        this.bot = bot;
        this.projectileHandler = projectileHandler;
    }

    /**
     * Evaluates the current combat situation and selects the best ranged action
     * to execute. Does NOT execute the action — returns the recommendation.
     *
     * @param target the current combat target
     * @return the recommended ranged action
     */
    @Nonnull
    public RangedAction evaluateBestAction(@Nullable LivingEntity target) {
        if (target == null) return RangedAction.NONE;

        LivingEntity botEntity = bot.getLivingEntity();
        Player player = bot.getPlayerEntity();
        if (botEntity == null || player == null) return RangedAction.NONE;

        DifficultyProfile diff = bot.getDifficultyProfile();
        double range = botEntity.getLocation().distance(target.getLocation());
        double healthFrac = botEntity.getHealth() / botEntity.getMaxHealth();

        UtilityItemHandler utilHandler = bot.getInventoryEngine() != null
                ? bot.getInventoryEngine().getUtilityItemHandler() : null;

        // ─── Critical situations first ──────────────────────────

        // Water push if enemy near void (very high value)
        if (utilHandler != null && utilHandler.hasWaterBucket() && utilHandler.isWaterReady()
                && range <= 5.0 && isTargetNearVoid(target)
                && diff.getWaterBucketMLG() >= 0.3) {
            return RangedAction.WATER_PUSH;
        }

        // Cobweb retreat when low HP
        if (utilHandler != null && utilHandler.hasCobweb() && utilHandler.isCobwebReady()
                && healthFrac < diff.getFleeHealthThreshold() * 1.3) {
            return RangedAction.COBWEB_RETREAT;
        }

        // ─── Range-based selection ──────────────────────────────

        // Long range (15+ blocks): bow power shot
        if (range > 15.0) {
            if (hasBow(player) && diff.getProjectileAccuracy() >= 0.4) {
                return RangedAction.BOW_POWER_SHOT;
            }
        }

        // Medium range (8-15 blocks): bow spam or throwables
        if (range > 8.0 && range <= 15.0) {
            if (hasBow(player)) {
                return RangedAction.BOW_SPAM;
            }
            if (player.getInventory().contains(Material.SNOW_BALL)) {
                return RangedAction.SNOWBALL;
            }
            if (player.getInventory().contains(Material.EGG)) {
                return RangedAction.EGG;
            }
        }

        // Close-medium range (4-8 blocks)
        if (range > 4.0 && range <= 8.0) {
            // TNT at this range if available
            if (utilHandler != null && utilHandler.hasTNT() && utilHandler.isTNTReady()
                    && diff.getDecisionQuality() >= 0.4) {
                return RangedAction.TNT;
            }

            // Fishing rod for combo setup
            if (projectileHandler.hasFishingRod() && diff.getRodUsageSkill() > 0.2
                    && RandomUtil.chance(diff.getRodUsageSkill())) {
                return RangedAction.FISHING_ROD;
            }

            // Throwables for approach KB
            if (player.getInventory().contains(Material.SNOW_BALL)
                    && projectileHandler.isProjectileReady()) {
                return RangedAction.SNOWBALL;
            }
            if (player.getInventory().contains(Material.EGG)
                    && projectileHandler.isProjectileReady()) {
                return RangedAction.EGG;
            }
        }

        // Close range (1.5-4 blocks): utility items
        if (range <= 4.0 && range >= 1.5) {
            // Lava for damage
            if (utilHandler != null && utilHandler.hasLavaBucket() && utilHandler.isLavaReady()
                    && diff.getDecisionQuality() >= 0.4) {
                return RangedAction.LAVA_PLACE;
            }

            // Flint & steel for fire
            if (utilHandler != null && utilHandler.hasFlintAndSteel() && utilHandler.isFlintReady()
                    && diff.getDecisionQuality() >= 0.3) {
                return RangedAction.FLINT_IGNITE;
            }

            // Cobweb trap
            if (utilHandler != null && utilHandler.hasCobweb() && utilHandler.isCobwebReady()
                    && diff.getDecisionQuality() >= 0.3) {
                return RangedAction.COBWEB_TRAP;
            }
        }

        return RangedAction.NONE;
    }

    /**
     * Executes the given ranged action against the target.
     *
     * @param action the action to execute
     * @param target the target entity
     * @return true if the action was successfully performed
     */
    public boolean executeAction(@Nonnull RangedAction action, @Nullable LivingEntity target) {
        if (target == null && action != RangedAction.COBWEB_RETREAT) return false;

        UtilityItemHandler utilHandler = bot.getInventoryEngine() != null
                ? bot.getInventoryEngine().getUtilityItemHandler() : null;

        switch (action) {
            case BOW_POWER_SHOT:
            case BOW_SPAM:
                return projectileHandler.tryBowShot();

            case SNOWBALL:
                return projectileHandler.trySnowball();

            case EGG:
                return projectileHandler.tryEgg();

            case FISHING_ROD:
                return projectileHandler.tryFishingRod();

            case ENDER_PEARL:
                if (target != null) {
                    return projectileHandler.tryEnderPearl(target.getLocation());
                }
                return false;

            case TNT:
                return utilHandler != null && target != null
                        && utilHandler.tryPlaceTNT(target);

            case WATER_PUSH:
                return utilHandler != null && target != null
                        && utilHandler.tryPlaceWaterOffensive(target.getLocation());

            case LAVA_PLACE:
                return utilHandler != null && target != null
                        && utilHandler.tryPlaceLavaOffensive(target);

            case FLINT_IGNITE:
                return utilHandler != null && target != null
                        && utilHandler.tryFlintAndSteel(target);

            case COBWEB_TRAP:
                return utilHandler != null && target != null
                        && utilHandler.tryPlaceCobweb(target);

            case COBWEB_RETREAT:
                return utilHandler != null
                        && utilHandler.tryPlaceCobwebBehind();

            case NONE:
            default:
                return false;
        }
    }

    /**
     * Convenience method: evaluates AND executes the best ranged action.
     *
     * @param target the combat target
     * @return true if any action was performed
     */
    public boolean tryBestRangedAction(@Nullable LivingEntity target) {
        RangedAction best = evaluateBestAction(target);
        if (best == RangedAction.NONE) return false;
        return executeAction(best, target);
    }

    // ─── Query Methods ──────────────────────────────────────────

    /**
     * Returns true if the bot has ANY ranged or utility item that can be used
     * against an enemy.
     *
     * @return true if any ranged/utility item is available
     */
    public boolean hasAnyRangedCapability() {
        Player player = bot.getPlayerEntity();
        if (player == null) return false;

        if (projectileHandler.hasRangedWeapons()) return true;

        UtilityItemHandler utilHandler = bot.getInventoryEngine() != null
                ? bot.getInventoryEngine().getUtilityItemHandler() : null;
        return utilHandler != null && utilHandler.hasAnyUtilityItem();
    }

    // ─── Helpers ────────────────────────────────────────────────

    private boolean hasBow(@Nonnull Player player) {
        return player.getInventory().contains(Material.BOW)
                && player.getInventory().contains(Material.ARROW);
    }

    private boolean isTargetNearVoid(@Nonnull LivingEntity target) {
        Location targetLoc = target.getLocation();
        if (targetLoc.getWorld() == null) return false;

        int[][] dirs = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}};
        int voidCount = 0;

        for (int[] dir : dirs) {
            Location check = targetLoc.clone().add(dir[0] * 2, 0, dir[1] * 2);
            boolean hasGround = false;
            for (int y = 0; y >= -10; y--) {
                if (check.clone().add(0, y, 0).getBlock().getType().isSolid()) {
                    hasGround = true;
                    break;
                }
            }
            if (!hasGround) voidCount++;
        }

        return voidCount >= 2;
    }
}
