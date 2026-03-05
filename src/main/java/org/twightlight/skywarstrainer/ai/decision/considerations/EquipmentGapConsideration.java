package org.twightlight.skywarstrainer.ai.decision.considerations;

import org.twightlight.skywarstrainer.ai.decision.DecisionContext;
import org.twightlight.skywarstrainer.ai.decision.UtilityScorer;
import org.twightlight.skywarstrainer.util.MathUtil;

import javax.annotation.Nonnull;

/**
 * Evaluates the equipment gap between the bot and perceived enemy gear.
 *
 * <p>Returns a score representing the bot's equipment ADVANTAGE or DISADVANTAGE.
 * A positive gap (bot has better gear) returns a high score, encouraging FIGHT/HUNT.
 * A negative gap (enemy has better gear) returns a low score, encouraging FLEE/LOOT.</p>
 *
 * <p>This consideration is consumed differently by different actions:
 * <ul>
 *   <li>FIGHT/HUNT actions use it as a direct multiplier (higher = more willing to fight)</li>
 *   <li>FLEE/LOOT actions use the INVERSE (lower gear advantage = more desire to retreat/loot)</li>
 * </ul></p>
 */
public final class EquipmentGapConsideration implements UtilityScorer {

    @Override
    public double score(@Nonnull DecisionContext context) {
        if (context.estimatedEnemyEquipmentScore < 0) {
            // Enemy gear unknown; return neutral score
            return 0.5;
        }

        // Gap: positive means bot is better, negative means enemy is better
        double gap = context.equipmentScore - context.estimatedEnemyEquipmentScore;

        /*
         * Map the gap [-1.0, 1.0] to a score [0.0, 1.0]:
         * gap = -1.0 (enemy much better) → score = 0.0
         * gap =  0.0 (even)              → score = 0.5
         * gap =  1.0 (bot much better)   → score = 1.0
         */
        double score = MathUtil.clamp((gap + 1.0) / 2.0, 0.0, 1.0);

        // Apply a slight sigmoid to make the response curve more dramatic at extremes
        return MathUtil.sigmoid(score, 6.0, 0.5);
    }

    @Override
    public String getName() {
        return "EquipmentGapConsideration";
    }
}

