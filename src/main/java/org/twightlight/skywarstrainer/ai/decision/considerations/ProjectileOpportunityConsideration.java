package org.twightlight.skywarstrainer.ai.decision.considerations;

import org.twightlight.skywarstrainer.ai.decision.DecisionContext;
import org.twightlight.skywarstrainer.ai.decision.UtilityScorer;
import org.twightlight.skywarstrainer.util.MathUtil;

import javax.annotation.Nonnull;

/**
 * Evaluates opportunities for ranged/projectile attacks.
 *
 * <p>Returns a high score when:
 * <ul>
 *   <li>An enemy is on a bridge (knockback = void kill opportunity)</li>
 *   <li>An enemy is near a void edge</li>
 *   <li>The bot has projectiles (bow, snowballs, eggs) available</li>
 *   <li>The enemy is at a favorable range for ranged attacks (15-30 blocks)</li>
 * </ul></p>
 *
 * <p>This consideration heavily biases SNIPER personality bots and influences
 * the combat engine to choose ranged strategies over melee.</p>
 */
public final class ProjectileOpportunityConsideration implements UtilityScorer {

    @Override
    public double score(@Nonnull DecisionContext context) {
        // No projectiles available — no opportunity
        boolean hasRanged = context.hasBow && context.arrowCount > 0;
        boolean hasThrowable = context.hasProjectiles;
        boolean hasFishingRod = context.hasFishingRod;

        if (!hasRanged && !hasThrowable && !hasFishingRod) {
            return 0.0;
        }

        // No enemies visible — no target
        if (context.visibleEnemyCount == 0) {
            return 0.0;
        }

        double score = 0.0;

        // Enemy on bridge = prime knockback opportunity
        if (context.enemyOnBridge) {
            score += 0.5;
            if (hasThrowable) score += 0.2; // Snowballs/eggs are perfect for this
        }

        // Enemy near void edge = knockback kill opportunity
        if (context.enemyNearVoid) {
            score += 0.3;
        }

        // Ideal ranged distance (15-30 blocks)
        if (context.nearestEnemyDistance >= 10 && context.nearestEnemyDistance <= 35) {
            double rangeScore = 1.0 - Math.abs(context.nearestEnemyDistance - 20) / 20.0;
            score += rangeScore * 0.2;
        }

        // Having a bow with arrows is the strongest ranged tool
        if (hasRanged && context.arrowCount >= 5) {
            score += 0.1;
        }

        return MathUtil.clamp(score, 0.0, 1.0);
    }

    @Override
    public String getName() {
        return "ProjectileOpportunityConsideration";
    }
}

