package org.twightlight.skywarstrainer.ai.decision.considerations;

import org.twightlight.skywarstrainer.ai.decision.DecisionContext;
import org.twightlight.skywarstrainer.ai.decision.UtilityScorer;

import javax.annotation.Nonnull;

/**
 * Utility consideration that returns higher scores for actions aligned with
 * the current strategy plan's phase.
 *
 * <p>This consideration does NOT directly read from the StrategyPlanner — that
 * integration is handled by the DecisionEngine's {@code applyStrategyMultipliers()}
 * step. Instead, this consideration provides a soft contextual signal: when a plan
 * is active and confident, the general "strategic coherence" of the bot's behavior
 * is boosted, which helps the DecisionEngine favor plan-aligned actions over
 * slightly higher-scoring but strategically incoherent alternatives.</p>
 *
 * <p>Scores: [0.3, 0.9] based on plan confidence. Returns 0.5 (neutral) when
 * no plan is active.</p>
 */
public class StrategyAlignmentConsideration implements UtilityScorer {

    @Override
    public double score(@Nonnull DecisionContext context) {
        // If no active plan, return neutral
        if (!context.hasActivePlan) {
            return 0.5;
        }

        // Higher score when plan confidence is high — this rewards consistency
        // The actual action-specific biasing is done by applyStrategyMultipliers,
        // but this consideration gives a general "stay on plan" signal that
        // gets weighted differently per action in buildActionWeights().
        double confidenceBonus = context.planConfidence * 0.4;
        return 0.5 + confidenceBonus; // Range: [0.5, 0.9]
    }

    @Override
    public String getName() {
        return "StrategyAlignment";
    }
}
