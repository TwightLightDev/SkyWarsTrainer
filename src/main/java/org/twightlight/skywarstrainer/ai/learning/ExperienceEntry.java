package org.twightlight.skywarstrainer.ai.learning;

import java.util.Arrays;

/**
 * Data class representing a single in-game experience tuple before it is
 * promoted to a {@link ReplayEntry} in the replay buffer.
 *
 * <p>An experience captures the transition: the bot was in {@code state},
 * took {@code actionOrdinal}, received {@code reward}, and ended up in
 * {@code nextState}. If {@code terminal} is true, the episode ended
 * (bot died or game ended) and there is no meaningful next state to
 * bootstrap from.</p>
 */
public class ExperienceEntry {

    /** 16-element normalized state vector at the time of the decision. Defensive copy. */
    public final double[] state;

    /** The ordinal of the BotAction taken (from BotAction.ordinal()). */
    public final int actionOrdinal;

    /** The total reward received for this transition (event-based + shaping). May be updated. */
    public double reward;

    /** 16-element normalized state vector after the transition. Defensive copy. */
    public final double[] nextState;

    /** True if this was a terminal transition (death or game end). */
    public final boolean terminal;

    /** The game number when this experience was recorded. */
    public final long gameNumber;

    /** The discretized state ID for Q-table lookup (computed from state vector). */
    public int discretizedStateId;

    /** The discretized next-state ID for Q-table lookup (computed from nextState vector). */
    public int discretizedNextStateId;

    /**
     * Creates a new ExperienceEntry with defensive copies of the state vectors.
     *
     * @param state          16-element state vector (will be copied)
     * @param actionOrdinal  BotAction.ordinal()
     * @param reward         total reward for this transition
     * @param nextState      16-element next-state vector (will be copied)
     * @param terminal       true if episode ended after this transition
     * @param gameNumber     the game number when recorded
     */
    public ExperienceEntry(double[] state, int actionOrdinal, double reward,
                           double[] nextState, boolean terminal, long gameNumber) {
        this.state = Arrays.copyOf(state, state.length);
        this.actionOrdinal = actionOrdinal;
        this.reward = reward;
        this.nextState = Arrays.copyOf(nextState, nextState.length);
        this.terminal = terminal;
        this.gameNumber = gameNumber;
        this.discretizedStateId = 0;
        this.discretizedNextStateId = 0;
    }

    @Override
    public String toString() {
        return "ExperienceEntry{action=" + actionOrdinal
                + ", reward=" + String.format("%.3f", reward)
                + ", terminal=" + terminal
                + ", game=" + gameNumber + "}";
    }
}
