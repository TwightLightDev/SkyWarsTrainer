package org.twightlight.skywarstrainer.ai.decision.considerations;

import org.twightlight.skywarstrainer.ai.decision.DecisionContext;
import org.twightlight.skywarstrainer.ai.decision.UtilityScorer;
import org.twightlight.skywarstrainer.util.MathUtil;

import javax.annotation.Nonnull;

/**
 * Evaluates the bot's current health status.
 *
 * <p>Returns a high score when health is LOW (signaling urgency for healing/fleeing).
 * Uses a modified sigmoid curve so the score rises sharply as health approaches
 * the flee threshold defined in the difficulty profile.</p>
 *
 * <p>Response curve:
 * <pre>
 *   HP fraction 1.0 (full)  → score ~0.0  (healthy, no urgency)
 *   HP fraction 0.5 (half)  → score ~0.3  (moderate concern)
 *   HP fraction 0.3 (low)   → score ~0.7  (high urgency)
 *   HP fraction 0.1 (crit)  → score ~0.95 (emergency)
 * </pre></p>
 *
 * <p>Absorption hearts (from golden apples) slightly reduce the score since
 * the bot has extra effective HP.</p>
 */
public final class HealthConsideration implements UtilityScorer {

    @Override
    public double score(@Nonnull DecisionContext context) {
        double hpFraction = context.healthFraction;

        // Absorption hearts provide an effective HP buffer
        if (context.hasAbsorption) {
            hpFraction = Math.min(1.0, hpFraction + 0.15);
        }

        /*
         * Inverted sigmoid curve centered at the flee threshold. The curve is
         * inverted so LOW health produces HIGH scores. The steepness is 12 to
         * create a sharp rise as health drops below the threshold.
         */
        double fleeThreshold = (context.difficultyProfile != null)
                ? context.difficultyProfile.getFleeHealthThreshold()
                : 0.3;

        // Modified sigmoid: high when health is low, zero when health is high
        double urgency = MathUtil.sigmoid(hpFraction, -12.0, fleeThreshold + 0.15);

        return MathUtil.clamp(urgency, 0.0, 1.0);
    }

    @Override
    public String getName() {
        return "HealthConsideration";
    }
}

