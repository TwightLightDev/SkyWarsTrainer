package org.twightlight.skywarstrainer.ai.decision.considerations;

import org.twightlight.skywarstrainer.ai.decision.DecisionContext;
import org.twightlight.skywarstrainer.ai.decision.UtilityScorer;
import org.twightlight.skywarstrainer.util.MathUtil;

import javax.annotation.Nonnull;

/**
 * Evaluates how much value the bot would gain from looting right now.
 *
 * <p>Returns a high score when:
 * <ul>
 *   <li>The bot's inventory is empty or poorly equipped</li>
 *   <li>There are unlooted chests nearby</li>
 *   <li>The game is in early phase (looting is most valuable early)</li>
 * </ul></p>
 *
 * <p>Returns a low score when:
 * <ul>
 *   <li>The bot is fully geared (diminishing returns)</li>
 *   <li>No chests are available</li>
 *   <li>The game is in late phase (fighting is more urgent)</li>
 * </ul></p>
 */
public final class LootValueConsideration implements UtilityScorer {

    @Override
    public double score(@Nonnull DecisionContext context) {
        // If no chests available, looting is worthless
        if (context.unlootedChestCount == 0) {
            return 0.0;
        }

        // Equipment gap: how much does the bot NEED loot?
        // Low equipment = high loot desire
        double needScore = MathUtil.invertedLinearCurve(context.equipmentScore, 0.0, 0.85);

        // Chest availability: more unlooted chests = higher opportunity
        double availabilityScore = MathUtil.linearCurve(context.unlootedChestCount, 0, 6);

        // Proximity: closer chests are more attractive
        double proximityScore;
        if (context.nearestChestDistance < 10) {
            proximityScore = 1.0;
        } else if (context.nearestChestDistance < 30) {
            proximityScore = MathUtil.invertedLinearCurve(context.nearestChestDistance, 10, 30);
        } else {
            proximityScore = 0.1; // Very far chests are barely worth it
        }

        // Game phase scaling: looting is most valuable early
        double phaseMultiplier;
        if (context.gamePhase != null) {
            switch (context.gamePhase) {
                case EARLY: phaseMultiplier = 1.0; break;
                case MID: phaseMultiplier = 0.6; break;
                case LATE: phaseMultiplier = 0.2; break;
                default: phaseMultiplier = 0.5;
            }
        } else {
            phaseMultiplier = 0.7;
        }

        double score = (needScore * 0.4 + availabilityScore * 0.25 + proximityScore * 0.35)
                * phaseMultiplier;

        return MathUtil.clamp(score, 0.0, 1.0);
    }

    @Override
    public String getName() {
        return "LootValueConsideration";
    }
}

