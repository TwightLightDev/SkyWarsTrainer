package org.twightlight.skywarstrainer.ai.decision.considerations;

import org.twightlight.skywarstrainer.ai.decision.DecisionContext;
import org.twightlight.skywarstrainer.ai.decision.UtilityScorer;
import org.twightlight.skywarstrainer.util.MathUtil;

import javax.annotation.Nonnull;

/**
 * Evaluates the strategic value of the bot's current position and zone control.
 *
 * <p>Returns a high score when:
 * <ul>
 *   <li>The bot controls mid island (strategic advantage)</li>
 *   <li>The bot is in a defensible position</li>
 *   <li>Enemies are forced through chokepoints to reach the bot</li>
 * </ul></p>
 *
 * <p>Returns a low score when the bot needs to move to a better position.
 * Used by CAMP and BRIDGE_TO_MID actions to evaluate territorial goals.</p>
 */
public final class ZoneControlConsideration implements UtilityScorer {

    @Override
    public double score(@Nonnull DecisionContext context) {
        double score = 0.0;

        // Controlling mid island is strategically valuable (best loot, central position)
        if (context.onMidIsland) {
            score += 0.5;
        }

        // Being near void edges is dangerous → lower zone control
        if (context.nearVoidEdge && context.voidEdgeDistance < 3.0) {
            score -= 0.2;
        }

        // Having a bridge to mid provides strategic options
        if (context.bridgeToMidExists) {
            score += 0.15;
        }

        // Being near lava is slightly dangerous
        if (context.nearLava) {
            score -= 0.1;
        }

        // Having an enchanting table nearby is a zone advantage
        if (context.enchantingTableAccessible) {
            score += 0.15;
        }

        // High ground / defensive position (simplified: if not near void, moderately safe)
        if (!context.nearVoidEdge && context.voidEdgeDistance > 5.0) {
            score += 0.1;
        }

        return MathUtil.clamp(score, 0.0, 1.0);
    }

    @Override
    public String getName() {
        return "ZoneControlConsideration";
    }
}

