package org.twightlight.skywarstrainer.combat;

import org.bukkit.Material;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.twightlight.skywarstrainer.bot.TrainerBot;
import org.twightlight.skywarstrainer.config.DifficultyConfig.DifficultyProfile;
import org.twightlight.skywarstrainer.util.RandomUtil;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * [FIX A2] REFACTORED: RangedCombatHandler is now a thin delegator for
 * PROJECTILE-ONLY ranged actions (bow, snowball, egg, rod, ender pearl).
 *
 * ALL utility item logic (water, lava, flint, TNT, cobweb) has been REMOVED.
 * Those are exclusively handled by UtilityItemStrategy via UtilityItemHandler.
 * This eliminates the overlap where RangedCombatHandler was making the same
 * decisions as UtilityItemStrategy + ProjectilePvPStrategy.
 *
 * This handler is ONLY called from CombatEngine.tick() step 7.5 as a fallback
 * when no combat strategy is actively handling ranged (i.e., ProjectilePvP and
 * RodCombo and UtilityItem are not the active strategy).
 */
public class RangedCombatHandler {

    private final TrainerBot bot;
    private final ProjectileHandler projectileHandler;

    public enum RangedAction {
        BOW_POWER_SHOT,
        BOW_SPAM,
        SNOWBALL,
        EGG,
        FISHING_ROD,
        ENDER_PEARL,
        NONE
    }

    public RangedCombatHandler(@Nonnull TrainerBot bot,
                               @Nonnull ProjectileHandler projectileHandler) {
        this.bot = bot;
        this.projectileHandler = projectileHandler;
    }

    /**
     * Evaluates the best PROJECTILE-ONLY ranged action. Does NOT handle utility items.
     */
    @Nonnull
    public RangedAction evaluateBestAction(@Nullable LivingEntity target) {
        if (target == null) return RangedAction.NONE;

        LivingEntity botEntity = bot.getLivingEntity();
        Player player = bot.getPlayerEntity();
        if (botEntity == null || player == null) return RangedAction.NONE;

        DifficultyProfile diff = bot.getDifficultyProfile();
        double range = botEntity.getLocation().distance(target.getLocation());

        // Long range (15+ blocks): bow power shot
        if (range > 15.0 && CombatUtils.hasBow(player) && diff.getProjectileAccuracy() >= 0.4) {
            return RangedAction.BOW_POWER_SHOT;
        }

        // Medium range (8-15 blocks): bow spam or throwables
        if (range > 8.0 && range <= 15.0) {
            if (CombatUtils.hasBow(player)) return RangedAction.BOW_SPAM;
            if (player.getInventory().contains(Material.SNOW_BALL)) return RangedAction.SNOWBALL;
            if (player.getInventory().contains(Material.EGG)) return RangedAction.EGG;
        }

        // Close-medium range (4-8 blocks)
        if (range > 4.0 && range <= 8.0) {
            // Fishing rod for combo setup
            if (projectileHandler.hasFishingRod() && diff.getRodUsageSkill() > 0.2
                    && RandomUtil.chance(diff.getRodUsageSkill())) {
                return RangedAction.FISHING_ROD;
            }
            if (player.getInventory().contains(Material.SNOW_BALL)
                    && projectileHandler.isProjectileReady()) {
                return RangedAction.SNOWBALL;
            }
            if (player.getInventory().contains(Material.EGG)
                    && projectileHandler.isProjectileReady()) {
                return RangedAction.EGG;
            }
        }

        return RangedAction.NONE;
    }

    /**
     * Executes the given projectile-only ranged action.
     */
    public boolean executeAction(@Nonnull RangedAction action, @Nullable LivingEntity target) {
        if (target == null) return false;

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
                return projectileHandler.tryEnderPearl(target.getLocation());
            case NONE:
            default:
                return false;
        }
    }

    /**
     * Convenience: evaluate AND execute.
     */
    public boolean tryBestRangedAction(@Nullable LivingEntity target) {
        RangedAction best = evaluateBestAction(target);
        if (best == RangedAction.NONE) return false;
        return executeAction(best, target);
    }

    public boolean hasAnyRangedCapability() {
        Player player = bot.getPlayerEntity();
        if (player == null) return false;
        return projectileHandler.hasRangedWeapons();
    }
}
