package org.twightlight.skywarstrainer.combat.strategies;

import org.bukkit.Material;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.twightlight.skywarstrainer.bot.TrainerBot;
import org.twightlight.skywarstrainer.combat.CombatUtils;
import org.twightlight.skywarstrainer.config.DifficultyConfig.DifficultyProfile;
import org.twightlight.skywarstrainer.inventory.UtilityItemHandler;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.List;

/**
 * Combat strategy that uses utility items (lava bucket, flint & steel, TNT,
 * cobweb, water bucket) during fights for tactical advantage.
 *
 * <p>This strategy complements the existing ProjectilePvPStrategy by handling
 * non-projectile items that players commonly use in SkyWars PvP:
 * <ul>
 *   <li><b>Lava bucket</b>: placed at enemy feet for damage + fire</li>
 *   <li><b>Flint & steel</b>: ignite at enemy feet for fire damage</li>
 *   <li><b>TNT</b>: placed at medium range for area denial and knockback</li>
 *   <li><b>Cobweb</b>: placed at enemy position to trap/slow them</li>
 *   <li><b>Water bucket</b>: placed offensively to push enemies toward void</li>
 * </ul></p>
 *
 * <p>Item usage priority changes based on context:
 * <ul>
 *   <li>Enemy near void → water bucket push (highest priority)</li>
 *   <li>Enemy in melee range → lava / flint / cobweb</li>
 *   <li>Enemy at medium range → TNT</li>
 *   <li>Bot fleeing → cobweb behind self</li>
 * </ul></p>
 *
 * <p>Activation requires the bot to have at least one utility item and the
 * difficulty profile's {@code decisionQuality} must be above 0.2 (BEGINNER
 * bots rarely use utility items).</p>
 */
public class UtilityItemStrategy implements CombatStrategy {

    /**
     * Sub-modes for the utility strategy, determining which item to prioritize.
     */
    private enum Mode {
        /** Place water to push enemy off void edge. */
        WATER_PUSH,
        /** Place lava at enemy feet for damage. */
        LAVA_PLACE,
        /** Ignite at enemy feet with flint & steel. */
        FLINT_IGNITE,
        /** Place TNT at medium range for area denial. */
        TNT_PLACE,
        /** Place cobweb on enemy to slow them. */
        COBWEB_TRAP,
        /** Place cobweb behind self while retreating. */
        COBWEB_RETREAT,
        /** No suitable utility action. */
        IDLE
    }

    /** Current operating mode. */
    private Mode currentMode;

    /** Ticks in current mode without a switch. */
    private int modeTicks;

    /** Cooldown between mode re-evaluations. */
    private int modeEvalCooldown;

    /** Re-evaluation interval in ticks. */
    private static final int MODE_EVAL_INTERVAL = 15;

    /** Max ticks in a single mode before re-evaluation. */
    private static final int MAX_MODE_TICKS = 40;

    @Nonnull
    @Override
    public String getName() {
        return "UtilityItem";
    }

    @Override
    public boolean shouldActivate(@Nonnull TrainerBot bot) {
        DifficultyProfile diff = bot.getDifficultyProfile();
        // Minimum intelligence gate
        if (diff.getDecisionQuality() < 0.2) return false;

        // Must have at least one utility item
        UtilityItemHandler utilHandler = bot.getInventoryEngine().getUtilityItemHandler();
        if (utilHandler == null) return false;
        if (!utilHandler.hasAnyUtilityItem()) return false;

        // Must have a target
        LivingEntity target = CombatUtils.findNearestEnemy(bot);
        if (target == null) return false;

        LivingEntity botEntity = bot.getLivingEntity();
        if (botEntity == null) return false;

        double range = botEntity.getLocation().distance(target.getLocation());
        // Utility items work at close to medium range
        return range <= 10.0;
    }

    @Override
    public void execute(@Nonnull TrainerBot bot) {
        UtilityItemHandler utilHandler = bot.getInventoryEngine().getUtilityItemHandler();
        if (utilHandler == null) return;

        LivingEntity botEntity = bot.getLivingEntity();
        if (botEntity == null) return;

        DifficultyProfile diff = bot.getDifficultyProfile();

        modeTicks++;
        modeEvalCooldown--;

        // Re-evaluate mode
        if (modeEvalCooldown <= 0 || modeTicks >= MAX_MODE_TICKS) {
            currentMode = evaluateBestMode(bot, utilHandler, diff);
            modeTicks = 0;
            modeEvalCooldown = MODE_EVAL_INTERVAL;
        }

        LivingEntity target = CombatUtils.findNearestEnemy(bot);
        if (target == null) return;

        switch (currentMode) {
            case WATER_PUSH:
                utilHandler.tryPlaceWaterOffensive(target.getLocation());
                break;

            case LAVA_PLACE:
                utilHandler.tryPlaceLavaOffensive(target);
                break;

            case FLINT_IGNITE:
                utilHandler.tryFlintAndSteel(target);
                break;

            case TNT_PLACE:
                utilHandler.tryPlaceTNT(target);
                break;

            case COBWEB_TRAP:
                utilHandler.tryPlaceCobweb(target);
                break;

            case COBWEB_RETREAT:
                utilHandler.tryPlaceCobwebBehind();
                break;

            case IDLE:
            default:
                break;
        }
    }

    /**
     * Evaluates the best utility item mode for the current situation.
     */
    @Nonnull
    private Mode evaluateBestMode(@Nonnull TrainerBot bot,
                                  @Nonnull UtilityItemHandler utilHandler,
                                  @Nonnull DifficultyProfile diff) {
        LivingEntity target = CombatUtils.findNearestEnemy(bot);
        if (target == null) return Mode.IDLE;

        LivingEntity botEntity = bot.getLivingEntity();
        if (botEntity == null) return Mode.IDLE;

        double range = botEntity.getLocation().distance(target.getLocation());
        double healthFrac = botEntity.getHealth() / botEntity.getMaxHealth();

        // Priority 1: Water push if enemy is near void
        if (utilHandler.hasWaterBucket() && utilHandler.isWaterReady()
                && CombatUtils.isTargetNearVoid(target) && range <= 5.0) {
            return Mode.WATER_PUSH;
        }

        // Priority 2: Cobweb retreat if bot is low HP and fleeing
        if (healthFrac < diff.getFleeHealthThreshold() * 1.3
                && utilHandler.hasCobweb() && utilHandler.isCobwebReady()) {
            return Mode.COBWEB_RETREAT;
        }

        // Priority 3: TNT at medium range
        if (utilHandler.hasTNT() && utilHandler.isTNTReady()
                && range >= 4.0 && range <= 8.0) {
            return Mode.TNT_PLACE;
        }

        // Priority 4: Lava at close range
        if (utilHandler.hasLavaBucket() && utilHandler.isLavaReady()
                && range >= 1.5 && range <= 5.0) {
            return Mode.LAVA_PLACE;
        }

        // Priority 5: Flint & steel at very close range
        if (utilHandler.hasFlintAndSteel() && utilHandler.isFlintReady()
                && range <= 4.0) {
            return Mode.FLINT_IGNITE;
        }

        // Priority 6: Cobweb trap at close range
        if (utilHandler.hasCobweb() && utilHandler.isCobwebReady()
                && range <= 5.0) {
            return Mode.COBWEB_TRAP;
        }

        return Mode.IDLE;
    }

    @Override
    public double getPriority(@Nonnull TrainerBot bot) {
        DifficultyProfile diff = bot.getDifficultyProfile();
        double base = 4.0 * diff.getDecisionQuality();

        LivingEntity target = CombatUtils.findNearestEnemy(bot);
        if (target == null) return 0.0;

        LivingEntity botEntity = bot.getLivingEntity();
        if (botEntity == null) return 0.0;

        UtilityItemHandler utilHandler = bot.getInventoryEngine().getUtilityItemHandler();
        if (utilHandler == null) return 0.0;

        double range = botEntity.getLocation().distance(target.getLocation());

        // Very high priority if target is near void and we have water
        if (CombatUtils.isTargetNearVoid(target) && utilHandler.hasWaterBucket()) {
            base += 7.0;
        }

        // High priority for lava/flint at close range
        if (range <= 4.0) {
            if (utilHandler.hasLavaBucket()) base += 3.0;
            if (utilHandler.hasFlintAndSteel()) base += 2.0;
        }

        // TNT bonus at medium range
        if (range >= 4.0 && range <= 8.0 && utilHandler.hasTNT()) {
            base += 3.5;
        }

        // Cobweb bonus during flee situations
        double healthFrac = botEntity.getHealth() / botEntity.getMaxHealth();
        if (healthFrac < diff.getFleeHealthThreshold() * 1.3 && utilHandler.hasCobweb()) {
            base += 4.0;
        }

        return base;
    }

    @Override
    public void reset() {
        currentMode = Mode.IDLE;
        modeTicks = 0;
        modeEvalCooldown = 0;
    }

}
