package org.twightlight.skywarstrainer.ai.decision.considerations;

import org.twightlight.skywarstrainer.ai.decision.DecisionContext;
import org.twightlight.skywarstrainer.ai.decision.UtilityScorer;
import org.twightlight.skywarstrainer.awareness.ThreatPredictor;

import javax.annotation.Nonnull;
import java.util.Map;
import java.util.UUID;

/**
 * Utility consideration that scores actions based on predicted enemy behavior.
 *
 * <p>This consideration reads from the {@link ThreatPredictor}'s predictions
 * (stored in the DecisionContext) and adjusts scores accordingly:</p>
 * <ul>
 *   <li>If an enemy is predicted APPROACHING_BOT → increases urgency for combat/flee</li>
 *   <li>If an enemy is predicted BRIDGING → increases BREAK_ENEMY_BRIDGE desirability</li>
 *   <li>If an enemy is predicted LOOTING → creates an opportunity window for rushing</li>
 *   <li>If an enemy is predicted FLEEING → encourages pursuit</li>
 *   <li>If an enemy is predicted CAMPING → suggests flanking or ranged engagement</li>
 * </ul>
 *
 * <p>This is a context-sensitive consideration — it returns different scores depending
 * on which action it's evaluating. The DecisionEngine registers it with appropriate
 * weight for different actions.</p>
 */
public class ThreatPredictionConsideration implements UtilityScorer {

    /**
     * The type of threat response this consideration evaluates.
     * Different instances are created for different action categories.
     */
    public enum ResponseType {
        /** Evaluates urgency for fight/engage actions. */
        COMBAT_URGENCY,
        /** Evaluates urgency for flee/heal actions. */
        DEFENSIVE_URGENCY,
        /** Evaluates opportunity for bridge-cutting. */
        BRIDGE_CUT_OPPORTUNITY,
        /** Evaluates opportunity for hunting/pursuing. */
        PURSUIT_OPPORTUNITY
    }

    private final ResponseType responseType;

    /**
     * Creates a new ThreatPredictionConsideration.
     *
     * @param responseType the type of response this consideration evaluates
     */
    public ThreatPredictionConsideration(@Nonnull ResponseType responseType) {
        this.responseType = responseType;
    }

    @Override
    public double score(@Nonnull DecisionContext context) {
        // Check if threat predictions are available
        if (context.threatPredictions == null || context.threatPredictions.isEmpty()) {
            return 0.5; // Neutral when no predictions
        }

        switch (responseType) {
            case COMBAT_URGENCY:
                return scoreCombatUrgency(context);
            case DEFENSIVE_URGENCY:
                return scoreDefensiveUrgency(context);
            case BRIDGE_CUT_OPPORTUNITY:
                return scoreBridgeCutOpportunity(context);
            case PURSUIT_OPPORTUNITY:
                return scorePursuitOpportunity(context);
            default:
                return 0.5;
        }
    }

    /**
     * Scores combat urgency: high when enemies are approaching the bot.
     */
    private double scoreCombatUrgency(@Nonnull DecisionContext context) {
        double maxScore = 0.0;

        for (ThreatPredictor.PredictedBehavior prediction : context.threatPredictions.values()) {
            double score = 0.0;
            double conf = prediction.confidence;

            switch (prediction.intent) {
                case APPROACHING_BOT:
                    // Enemy coming toward us — high combat urgency
                    score = 0.8 * conf;
                    break;
                case BRIDGING:
                    // Enemy bridging could be toward us — moderate urgency
                    score = 0.5 * conf;
                    break;
                case CAMPING:
                case LOOTING:
                    // Not approaching — low urgency
                    score = 0.2 * conf;
                    break;
                case FLEEING:
                    // Fleeing — moderate (opportunity to chase)
                    score = 0.4 * conf;
                    break;
                default:
                    score = 0.3 * conf;
                    break;
            }

            if (score > maxScore) {
                maxScore = score;
            }
        }

        return Math.min(1.0, maxScore);
    }

    /**
     * Scores defensive urgency: high when immediate threats are detected.
     */
    private double scoreDefensiveUrgency(@Nonnull DecisionContext context) {
        double maxThreat = 0.0;

        for (ThreatPredictor.PredictedBehavior prediction : context.threatPredictions.values()) {
            if (prediction.intent == ThreatPredictor.PredictedIntent.APPROACHING_BOT) {
                double threat = prediction.confidence;
                // Scale by proximity if we can estimate it
                if (context.nearestEnemyDistance < 10) {
                    threat *= 1.0 + (10.0 - context.nearestEnemyDistance) / 10.0;
                }
                if (threat > maxThreat) {
                    maxThreat = threat;
                }
            }
        }

        return Math.min(1.0, maxThreat);
    }

    /**
     * Scores bridge-cut opportunity: high when an enemy is actively bridging.
     */
    private double scoreBridgeCutOpportunity(@Nonnull DecisionContext context) {
        for (ThreatPredictor.PredictedBehavior prediction : context.threatPredictions.values()) {
            if (prediction.intent == ThreatPredictor.PredictedIntent.BRIDGING) {
                return 0.7 * prediction.confidence;
            }
        }
        return 0.0;
    }

    /**
     * Scores pursuit opportunity: high when an enemy is looting or fleeing.
     */
    private double scorePursuitOpportunity(@Nonnull DecisionContext context) {
        double maxOpportunity = 0.0;

        for (ThreatPredictor.PredictedBehavior prediction : context.threatPredictions.values()) {
            double opportunity = 0.0;

            switch (prediction.intent) {
                case LOOTING:
                    // Enemy distracted by loot — great pursuit window
                    opportunity = 0.7 * prediction.confidence;
                    break;
                case FLEEING:
                    // Enemy running — chase them down
                    opportunity = 0.8 * prediction.confidence;
                    break;
                case CAMPING:
                    // Stationary target — moderate opportunity
                    opportunity = 0.5 * prediction.confidence;
                    break;
                default:
                    break;
            }

            if (opportunity > maxOpportunity) {
                maxOpportunity = opportunity;
            }
        }

        return Math.min(1.0, maxOpportunity);
    }

    @Override
    public String getName() {
        return "ThreatPrediction(" + responseType.name() + ")";
    }
}
