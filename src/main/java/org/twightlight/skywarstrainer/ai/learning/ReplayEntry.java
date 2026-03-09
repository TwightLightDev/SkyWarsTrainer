package org.twightlight.skywarstrainer.ai.learning;

import java.util.Arrays;

/**
 * Data class representing a single experience tuple stored in the
 * {@link ReplayBuffer} for prioritized experience replay.
 *
 * <p>Each entry stores the full (s, a, r, s', done) transition along with
 * metadata for prioritized sampling: the TD-error priority and the game
 * number for staleness detection.</p>
 *
 * <p>State and nextState arrays are stored as defensive copies to prevent
 * external mutation from corrupting the replay buffer.</p>
 */
public class ReplayEntry {

    /** 16-element normalized state vector. Defensive copy. */
    public final double[] state;

    /** The ordinal of the BotAction taken. */
    public final int actionOrdinal;

    /** The total reward received for this transition. */
    public final double reward;

    /** 16-element normalized next-state vector. Defensive copy. */
    public final double[] nextState;

    /** True if this was a terminal transition (death or game end). */
    public final boolean terminal;

    /** The game number when this experience was recorded. */
    public final long gameNumber;

    /**
     * The priority for this entry in the replay buffer, based on the absolute
     * TD-error from the most recent training pass. Higher priority means the
     * experience is more "surprising" and thus more informative.
     *
     * <p>Mutable because it is updated after each mini-batch training round.</p>
     */
    double tdErrorPriority;

    /**
     * Creates a new ReplayEntry with defensive copies of the state vectors.
     *
     * @param state         16-element state vector (will be copied)
     * @param actionOrdinal BotAction.ordinal()
     * @param reward        total reward for this transition
     * @param nextState     16-element next-state vector (will be copied)
     * @param terminal      true if episode ended after this transition
     * @param gameNumber    the game number when recorded
     * @param initialPriority the initial TD-error priority
     */
    public ReplayEntry(double[] state, int actionOrdinal, double reward,
                       double[] nextState, boolean terminal, long gameNumber,
                       double initialPriority) {
        this.state = Arrays.copyOf(state, state.length);
        this.actionOrdinal = actionOrdinal;
        this.reward = reward;
        this.nextState = Arrays.copyOf(nextState, nextState.length);
        this.terminal = terminal;
        this.gameNumber = gameNumber;
        this.tdErrorPriority = initialPriority;
    }

    /**
     * Creates a ReplayEntry from an ExperienceEntry, computing an initial priority.
     *
     * @param entry           the experience entry to convert
     * @param initialPriority the initial TD-error priority (typically maxPriority)
     * @return a new ReplayEntry
     */
    public static ReplayEntry fromExperience(ExperienceEntry entry, double initialPriority) {
        return new ReplayEntry(
                entry.state,
                entry.actionOrdinal,
                entry.reward,
                entry.nextState,
                entry.terminal,
                entry.gameNumber,
                initialPriority
        );
    }

    /**
     * Returns the current TD-error priority.
     *
     * @return the priority value
     */
    public double getTdErrorPriority() {
        return tdErrorPriority;
    }

    /**
     * Deep-copies this entry. Used for snapshot-based serialization.
     *
     * @return a new ReplayEntry with copied arrays
     */
    public ReplayEntry deepCopy() {
        return new ReplayEntry(
                this.state,      // constructor already copies
                this.actionOrdinal,
                this.reward,
                this.nextState,  // constructor already copies
                this.terminal,
                this.gameNumber,
                this.tdErrorPriority
        );
    }

    @Override
    public String toString() {
        return "ReplayEntry{action=" + actionOrdinal
                + ", reward=" + String.format("%.3f", reward)
                + ", terminal=" + terminal
                + ", game=" + gameNumber
                + ", priority=" + String.format("%.4f", tdErrorPriority) + "}";
    }
}
