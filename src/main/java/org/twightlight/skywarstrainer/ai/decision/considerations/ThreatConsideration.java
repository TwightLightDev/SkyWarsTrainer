package org.twightlight.skywarstrainer.ai.decision.considerations;

import org.twightlight.skywarstrainer.ai.decision.DecisionContext;
import org.twightlight.skywarstrainer.ai.decision.UtilityScorer;
import org.twightlight.skywarstrainer.util.MathUtil;

import javax.annotation.Nonnull;

/**
 * Evaluates the threat level based on visible enemies' count, distance, and gear.
 *
 * <p>Returns a high score when threat is HIGH (enemies close, multiple enemies,
 * or enemies with superior gear). Used to bias toward FIGHT, FLEE, or CAMP
 * depending on which action's weights incorporate this consideration.</p>
 *
 * <p>Components:
 * <ul>
 *   <li>Proximity: enemies within melee range score highest</li>
 *   <li>Outnumber: multiple enemies amplify threat</li>
 *   <li>Gear disadvantage: enemy with better equipment increases threat</li>
 * </ul></p>
 */
public final class ThreatConsideration implements UtilityScorer {

    @Override
    public double score(@Nonnull DecisionContext context) {
        if (context.visibleEnemyCount == 0) {
            return 0.0; // No enemies, no threat
        }

        // Proximity threat: closer = higher
        double proximityScore;
        if (context.nearestEnemyDistance <= 4.0) {
            proximityScore = 1.0; // In melee range — maximum threat
        } else if (context.nearestEnemyDistance <= 15.0) {
            proximityScore = MathUtil.invertedLinearCurve(context.nearestEnemyDistance, 4.0, 15.0);
        } else {
            proximityScore = MathUtil.invertedLinearCurve(context.nearestEnemyDistance, 15.0, 60.0) * 0.3;
        }

        // Outnumber threat: multiple enemies = much more dangerous
        double outnumberScore;
        switch (context.visibleEnemyCount) {
            case 1: outnumberScore = 0.3; break;
            case 2: outnumberScore = 0.7; break;
            case 3: outnumberScore = 0.9; break;
            default: outnumberScore = 1.0; break;
        }

        // Gear disadvantage: if enemy has notably better equipment
        double gearScore = 0.0;
        if (context.estimatedEnemyEquipmentScore > 0
                && context.estimatedEnemyEquipmentScore > context.equipmentScore + 0.15) {
            gearScore = MathUtil.clamp(
                    (context.estimatedEnemyEquipmentScore - context.equipmentScore) * 2.0,
                    0.0, 1.0);
        }

        // Weighted combination: proximity is most important
        double threat = (proximityScore * 0.5) + (outnumberScore * 0.3) + (gearScore * 0.2);
        return MathUtil.clamp(threat, 0.0, 1.0);
    }

    @Override
    public String getName() {
        return "ThreatConsideration";
    }
}

