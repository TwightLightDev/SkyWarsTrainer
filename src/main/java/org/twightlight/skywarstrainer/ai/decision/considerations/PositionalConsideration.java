package org.twightlight.skywarstrainer.ai.decision.considerations;

import org.twightlight.skywarstrainer.ai.decision.DecisionContext;
import org.twightlight.skywarstrainer.ai.decision.UtilityScorer;

import javax.annotation.Nonnull;

/**
 * Utility consideration that reads the active positional strategy from the
 * {@link org.twightlight.skywarstrainer.movement.positional.PositionalStrategyManager}
 * and applies its utility bonuses to the scoring pipeline.
 *
 * <p>When a positional strategy is active (e.g., IslandRotation), it provides
 * multipliers like LOOT_OTHER_ISLAND ×2.0, FIGHT ×0.3. This consideration
 * injects those multipliers into the utility scoring.</p>
 *
 * <p>This consideration returns a score centered at 0.5 (neutral). Positive
 * deviations boost the action, negative deviations suppress it. The actual
 * bonuses are applied externally by the DecisionEngine after normal scoring.</p>
 */
public class PositionalConsideration implements UtilityScorer {

    /**
     * Returns a neutral score (0.5). The actual positional modifiers are
     * applied externally by DecisionEngine using
     * {@code PositionalStrategyManager.getActiveUtilityBonuses()}.
     *
     * <p>This consideration exists as a registration placeholder so the
     * DecisionEngine knows to query the positional manager. The real
     * application happens in the evaluate() method override.</p>
     *
     * @param context the decision context
     * @return 0.5 (neutral baseline)
     */
    @Override
    public double score(@Nonnull DecisionContext context) {
        // The PositionalStrategyManager utility bonuses are applied as
        // post-scoring multipliers in DecisionEngine.evaluate(), not as
        // a direct consideration score. This returns neutral to avoid
        // double-counting.
        return 0.5;
    }
}
