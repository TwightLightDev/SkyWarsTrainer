package org.twightlight.skywarstrainer.ai.decision.considerations;

import org.twightlight.skywarstrainer.ai.decision.DecisionContext;
import org.twightlight.skywarstrainer.ai.decision.UtilityScorer;
import org.twightlight.skywarstrainer.util.MathUtil;

import javax.annotation.Nonnull;

/**
 * Evaluates the urgency to act based on elapsed game time and player count.
 *
 * <p>Returns a score that rises as the game progresses. In early game, this is low
 * (encouraging looting). In late game / deathmatch, it approaches 1.0 (encouraging
 * aggressive play). Uses a quadratic curve so the pressure accelerates — like
 * a real player feeling the deathmatch timer approaching.</p>
 *
 * <p>This consideration directly uses the GamePhaseTracker's timePressure value,
 * which already implements a polynomial curve over game progress.</p>
 */
public final class TimePressureConsideration implements UtilityScorer {

    @Override
    public double score(@Nonnull DecisionContext context) {
        double baseTimePressure = context.timePressure;

        // Additional pressure from low player count (1v1 or 1v2 scenarios)
        double playerPressure = 0.0;
        if (context.alivePlayerCount <= 2) {
            playerPressure = 0.4; // Heavy pressure in 1v1
        } else if (context.alivePlayerCount <= 4) {
            playerPressure = 0.2; // Moderate pressure
        }

        double combinedPressure = Math.max(baseTimePressure, baseTimePressure + playerPressure);
        return MathUtil.clamp(combinedPressure, 0.0, 1.0);
    }

    @Override
    public String getName() {
        return "TimePressureConsideration";
    }
}

