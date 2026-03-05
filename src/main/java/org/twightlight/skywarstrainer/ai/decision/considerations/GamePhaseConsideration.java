package org.twightlight.skywarstrainer.ai.decision.considerations;

import org.twightlight.skywarstrainer.ai.decision.DecisionContext;
import org.twightlight.skywarstrainer.ai.decision.UtilityScorer;
import org.twightlight.skywarstrainer.util.MathUtil;

import javax.annotation.Nonnull;

/**
 * Returns the continuous game progress value as a consideration score.
 *
 * <p>This is a simple pass-through that exposes the game progress [0.0, 1.0]
 * as a utility consideration. Actions that should become more attractive as the
 * game progresses (FIGHT, HUNT) use this with a positive weight. Actions that
 * should diminish (LOOT, ENCHANT) use it inverted or with a negative weight.</p>
 *
 * <p>Unlike TimePressureConsideration (which uses a polynomial curve), this
 * provides the raw linear progress value for actions that need proportional scaling.</p>
 */
public final class GamePhaseConsideration implements UtilityScorer {

    @Override
    public double score(@Nonnull DecisionContext context) {
        return MathUtil.clamp(context.gameProgress, 0.0, 1.0);
    }

    @Override
    public String getName() {
        return "GamePhaseConsideration";
    }
}

