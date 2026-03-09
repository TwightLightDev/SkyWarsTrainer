package org.twightlight.skywarstrainer.ai.learning;

import org.twightlight.skywarstrainer.util.MathUtil;

import javax.annotation.Nonnull;
import java.util.List;

/**
 * Computes the scalar reward signal from game events and state transitions.
 *
 * <p>This is the "feedback" layer that tells the learning system whether a
 * decision was good or bad. Two reward sources are combined:</p>
 * <ol>
 *   <li><b>Event-based rewards</b>: Kill, death, loot, damage, etc. Each event
 *       type maps to a configurable reward value from learning_config.yml.</li>
 *   <li><b>Potential-based shaping rewards</b>: A small bonus/penalty based on
 *       how the bot's "situation quality" changed between states. This accelerates
 *       early learning without altering the optimal policy (Ng et al., 1999).</li>
 * </ol>
 *
 * <p>All reward values are configurable via {@link LearningConfig} and never hardcoded.</p>
 */
public class RewardCalculator {

    private final LearningConfig config;

    /**
     * Creates a new RewardCalculator.
     *
     * @param config the learning configuration containing reward values
     */
    public RewardCalculator(@Nonnull LearningConfig config) {
        this.config = config;
    }

    /**
     * Computes the total reward for a transition: event rewards + shaping reward.
     *
     * @param events     the list of game events that occurred since the last decision
     * @param prevState  the 16-element state vector at the time of the previous decision
     * @param currentState the 16-element state vector at the time of the current decision
     * @param gamma      the discount factor
     * @return the total reward
     */
    public double computeTotalReward(@Nonnull List<GameEvent> events,
                                     @Nonnull double[] prevState,
                                     @Nonnull double[] currentState,
                                     double gamma) {
        double eventReward = computeReward(events);
        double shapingReward = 0.0;
        if (config.isShapingEnabled()) {
            shapingReward = computeShapingReward(prevState, currentState, gamma);
        }
        return eventReward + shapingReward;
    }

    /**
     * Computes the sum of rewards from a list of game events.
     *
     * @param events the events since the last decision point
     * @return the summed reward
     */
    public double computeReward(@Nonnull List<GameEvent> events) {
        double total = 0.0;
        for (int i = 0; i < events.size(); i++) {
            GameEvent event = events.get(i);
            total += getRewardForEvent(event.type, event.value);
        }
        return total;
    }

    /**
     * Computes the potential-based shaping reward for a state transition.
     *
     * <p>Shaping reward: F(s, s') = gamma * Phi(s') - Phi(s)</p>
     * <p>Where Phi(s) = healthWeight * healthFraction + equipmentWeight * equipmentScore
     *                    + timeWeight * (1.0 - gameProgress)</p>
     *
     * <p>This gives a small bonus for transitions that improve the bot's situation
     * (gaining health, gaining gear, especially in early game). Potential-based shaping
     * is proven to preserve the optimal policy.</p>
     *
     * @param prevState    the previous state vector
     * @param currentState the current state vector
     * @param gamma        the discount factor
     * @return the shaping reward
     */
    public double computeShapingReward(@Nonnull double[] prevState,
                                       @Nonnull double[] currentState,
                                       double gamma) {
        double phiPrev = computePotential(prevState);
        double phiCurrent = computePotential(currentState);
        return gamma * phiCurrent - phiPrev;
    }

    /**
     * Computes the potential function Phi(s) for a state vector.
     *
     * <p>Phi(s) = healthWeight * state[0] + equipWeight * state[1]
     *            + timeWeight * (1.0 - state[4])</p>
     *
     * <p>state[0] = healthFraction, state[1] = equipmentScore, state[4] = gameProgress.</p>
     *
     * @param state the 16-element state vector
     * @return the potential value
     */
    private double computePotential(@Nonnull double[] state) {
        double healthFrac = (state.length > 0) ? state[0] : 0.0;
        double equipScore = (state.length > 1) ? state[1] : 0.0;
        double gameProgress = (state.length > 4) ? state[4] : 0.0;

        return config.getShapingHealthWeight() * healthFrac
                + config.getShapingEquipmentWeight() * equipScore
                + config.getShapingTimeWeight() * (1.0 - gameProgress);
    }

    /**
     * Returns the reward value for a single game event type.
     *
     * @param eventType the event type string
     * @param eventValue the event's numeric value (e.g., damage amount, hearts gained)
     * @return the reward for this event
     */
    private double getRewardForEvent(@Nonnull String eventType, double eventValue) {
        switch (eventType) {
            case "kill":
                return config.getRewardKill();
            case "win":
                return config.getRewardWin();
            case "death":
                return config.getRewardDeath();
            case "lose":
                return config.getRewardLose();
            case "weapon_upgrade":
                return config.getRewardWeaponUpgrade();
            case "armor_upgrade":
                return config.getRewardArmorUpgrade();
            case "health_gained":
                return config.getRewardHealthGainedPerHeart() * eventValue;
            case "health_lost":
                return config.getRewardHealthLostPerHeart() * Math.abs(eventValue);
            case "chest_looted":
                return config.getRewardChestLooted();
            case "enemy_fled":
                return config.getRewardEnemyFled();
            case "forced_to_flee":
                return config.getRewardForcedToFlee();
            case "bridge_completed":
                return config.getRewardBridgeCompleted();
            case "idle":
                return config.getRewardIdlePerSecond() * eventValue;
            case "survived_top_3":
                return config.getRewardSurvivedTop3();
            case "knockback_dealt_edge":
                return config.getRewardKnockbackDealtEdge();
            case "combo_landed":
                return config.getRewardComboLanded();
            default:
                return 0.0;
        }
    }

    // ═════════════════════════════════════════════════════════════
    //  INNER: GameEvent
    // ═════════════════════════════════════════════════════════════

    /**
     * A lightweight data holder representing a single game event that
     * occurred between decision points.
     *
     * <p>Events are accumulated by the ExperienceRecorder and passed to
     * the RewardCalculator in bulk when the next decision is made.</p>
     */
    public static class GameEvent {

        /** The event type (e.g., "kill", "death", "health_gained"). */
        public final String type;

        /** The event's numeric value (e.g., damage amount, hearts healed). */
        public final double value;

        /** The server tick when this event occurred. */
        public final long tickTimestamp;

        /**
         * Creates a new GameEvent.
         *
         * @param type          the event type string
         * @param value         the event's numeric value
         * @param tickTimestamp the server tick timestamp
         */
        public GameEvent(@Nonnull String type, double value, long tickTimestamp) {
            this.type = type;
            this.value = value;
            this.tickTimestamp = tickTimestamp;
        }

        @Override
        public String toString() {
            return "GameEvent{" + type + "=" + String.format("%.2f", value)
                    + ", tick=" + tickTimestamp + "}";
        }
    }
}
