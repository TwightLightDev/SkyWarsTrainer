package org.twightlight.skywarstrainer.ai.decision.considerations;

import org.twightlight.skywarstrainer.ai.decision.DecisionContext;
import org.twightlight.skywarstrainer.ai.decision.UtilityScorer;
import org.twightlight.skywarstrainer.combat.counter.EnemyBehaviorAnalyzer;

import javax.annotation.Nonnull;

/**
 * Utility consideration that factors enemy behavior analysis into the
 * utility scoring pipeline.
 *
 * <p>When the {@link EnemyBehaviorAnalyzer}
 * has counter recommendations for the primary threat, this consideration
 * adjusts FIGHT/FLEE/CAMP utility scores accordingly.</p>
 *
 * <p>Like {@link PositionalConsideration}, the actual modifiers are applied
 * externally by DecisionEngine as post-scoring multipliers. This class serves
 * as a registration placeholder.</p>
 */
public class CounterPlayConsideration implements UtilityScorer {

    /**
     * Returns a neutral score (0.5). Counter-play modifiers are applied
     * externally by DecisionEngine using CounterModifiers from the
     * EnemyBehaviorAnalyzer.
     *
     * @param context the decision context
     * @return 0.5 (neutral baseline)
     */
    @Override
    public double score(@Nonnull DecisionContext context) {
        return 0.5;
    }
}
