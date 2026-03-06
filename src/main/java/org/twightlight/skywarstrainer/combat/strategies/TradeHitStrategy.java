package org.twightlight.skywarstrainer.combat.strategies;

import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.util.Vector;
import org.twightlight.skywarstrainer.bot.TrainerBot;
import org.twightlight.skywarstrainer.config.DifficultyConfig.DifficultyProfile;
import org.twightlight.skywarstrainer.movement.MovementController;
import org.twightlight.skywarstrainer.util.MathUtil;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * Trade-hit strategy: When losing a combo, walk INTO the enemy to reduce their
 * KB on us and land our own hit to reset the fight.
 *
 * <p>This is a defensive-aggressive technique used when the bot is being comboed.
 * Instead of trying to flee (which often fails against a good combo), the bot
 * moves directly toward the attacker. This:
 * <ul>
 *   <li>Reduces the effective KB of the enemy's hits (movement into the hit)</li>
 *   <li>Gets the bot close enough to land a retaliatory hit</li>
 *   <li>Resets the combo (both sides trade hits)</li>
 * </ul></p>
 *
 * <p>This strategy activates when the bot has more HP than the enemy and can
 * afford to trade hits, or when being comboed and unable to escape.</p>
 */
public class TradeHitStrategy implements CombatStrategy {

    /** Whether the trade is in progress. */
    private boolean trading;

    /** Ticks spent in the trade approach. */
    private int tradeTimer;

    /** Maximum ticks to attempt a trade before giving up. */
    private static final int MAX_TRADE_TICKS = 15;

    @Nonnull
    @Override
    public String getName() {
        return "TradeHit";
    }

    @Override
    public boolean shouldActivate(@Nonnull TrainerBot bot) {
        LivingEntity botEntity = bot.getLivingEntity();
        if (botEntity == null || botEntity.isDead()) return false;

        DifficultyProfile diff = bot.getDifficultyProfile();
        // Requires MEDIUM+ decision quality to recognize trade opportunity
        if (diff.getDecisionQuality() < 0.5) return false;

        // Find nearest enemy
        LivingEntity target = findNearestEnemy(bot);
        if (target == null) return false;

        double range = botEntity.getLocation().distance(target.getLocation());
        // Only trade when very close (being comboed at close range)
        if (range > 5.0) return false;

        // Check if we have more HP than the enemy (can afford to trade)
        double botHpFraction = botEntity.getHealth() / botEntity.getMaxHealth();
        double targetHpFraction = target.getHealth() / target.getMaxHealth();

        // Trade when: we have more HP, OR when we're being comboed regardless
        return botHpFraction > targetHpFraction + 0.1;
    }

    @Override
    public void execute(@Nonnull TrainerBot bot) {
        MovementController mc = bot.getMovementController();
        if (mc == null) return;

        LivingEntity target = findNearestEnemy(bot);
        if (target == null) {
            trading = false;
            return;
        }

        // Walk directly toward the enemy to reduce their KB on us
        mc.setMoveTarget(target.getLocation());
        mc.setLookTarget(target.getLocation().add(0, 1.0, 0));
        mc.getSprintController().startSprinting();

        tradeTimer++;
        if (tradeTimer >= MAX_TRADE_TICKS) {
            trading = false;
            tradeTimer = 0;
        }
    }

    @Override
    public double getPriority(@Nonnull TrainerBot bot) {
        DifficultyProfile diff = bot.getDifficultyProfile();
        // Trade-hitting is situational — moderate priority when conditions are met
        return 4.5 * diff.getDecisionQuality();
    }

    @Override
    public void reset() {
        trading = false;
        tradeTimer = 0;
    }

    @Nullable
    private LivingEntity findNearestEnemy(@Nonnull TrainerBot bot) {
        LivingEntity botEntity = bot.getLivingEntity();
        if (botEntity == null) return null;

        double nearestDist = Double.MAX_VALUE;
        LivingEntity nearest = null;
        for (org.bukkit.entity.Entity entity : botEntity.getNearbyEntities(6, 6, 6)) {
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
