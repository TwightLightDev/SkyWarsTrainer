package org.twightlight.skywarstrainer.ai.state;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
/**
 * Immutable record of a state-machine transition that has occurred.
 *
 * <p>StateTransition objects are used by the {@link BotStateMachine} to log
 * transitions (for debug output) and fire the
 * {@link org.twightlight.skywarstrainer.api.events.BotStateChangeEvent}
 * (Phase 6). They carry the source state, target state, and an optional
 * human-readable reason for why the transition was triggered.</p>
 */
public final class StateTransition {

    private final BotState fromState;
    private final BotState toState;
    private final String   reason;
    private final long     tickNumber;

    /**
     * Creates a state transition record.
     *
     * @param fromState  the state being left
     * @param toState    the state being entered
     * @param reason     human-readable reason (may be null for debug purposes)
     * @param tickNumber the bot's local tick count when the transition occurred
     */
    public StateTransition(@Nonnull BotState fromState, @Nonnull BotState toState,
                           @Nullable String reason, long tickNumber) {
        this.fromState  = fromState;
        this.toState    = toState;
        this.reason     = (reason != null) ? reason : "unspecified";
        this.tickNumber = tickNumber;
    }

    /**
     * Returns the state that was active before this transition.
     *
     * @return the source state
     */
    @Nonnull
    public BotState getFromState() {
        return fromState;
    }

    /**
     * Returns the state that became active after this transition.
     *
     * @return the target state
     */
    @Nonnull
    public BotState getToState() {
        return toState;
    }

    /**
     * Returns the human-readable reason for this transition.
     *
     * @return the reason string
     */
    @Nonnull
    public String getReason() {
        return reason;
    }

    /**
     * Returns the bot-local tick count when this transition occurred.
     *
     * @return tick number
     */
    public long getTickNumber() {
        return tickNumber;
    }

    @Override
    public String toString() {
        return "Transition[" + fromState + " -> " + toState
                + " @ tick " + tickNumber + " (" + reason + ")]";
    }
}
