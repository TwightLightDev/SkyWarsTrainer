package org.twightlight.skywarstrainer.ai.learning;

import org.twightlight.skywarstrainer.ai.decision.DecisionEngine.BotAction;
import org.twightlight.skywarstrainer.util.DebugLogger;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Records the stream of (state, action, reward, nextState) tuples during
 * gameplay and manages the per-game experience buffer.
 *
 * <p>The recorder is notified each time the DecisionEngine selects a new action.
 * At that point, the previous decision's transition is complete: we know the
 * previous state, the action taken, the events that occurred, and the resulting
 * new state. The recorder computes the reward, creates an ExperienceEntry,
 * and accumulates it in the pending list.</p>
 *
 * <p>At game end, all pending experiences are flushed to the ReplayBuffer and
 * mini-batch replay training is performed. The recorder also coordinates with
 * the EligibilityTraceTable for within-game TD(λ) updates.</p>
 *
 * <p>The recorder is per-bot but writes to the shared MemoryBank and ReplayBuffer.</p>
 */
public class ExperienceRecorder {

    private final StateEncoder stateEncoder;
    private final RewardCalculator rewardCalculator;
    private final MemoryBank memoryBank;
    private final LearningConfig config;

    // ── Per-game accumulator ──

    /** The current game's raw experiences (flushed to ReplayBuffer at game end). */
    private final List<ExperienceEntry> pendingExperiences;

    /** Events accumulated since the last decision point. */
    private final List<RewardCalculator.GameEvent> pendingEvents;

    /** The state vector at the time of the last decision (copy, not pooled). */
    private double[] previousStateVector;

    /** The discretized state ID at the time of the last decision. */
    private int previousStateId;

    /** The action ordinal selected at the last decision. */
    private int previousActionOrdinal;

    /** Whether there is a pending (unresolved) decision waiting for its outcome. */
    private boolean hasPendingDecision;

    /**
     * Creates a new ExperienceRecorder.
     *
     * @param stateEncoder      the state encoder for discretization
     * @param rewardCalculator  the reward calculator
     * @param memoryBank        the shared memory bank
     * @param config            the learning configuration
     */
    public ExperienceRecorder(@Nonnull StateEncoder stateEncoder,
                              @Nonnull RewardCalculator rewardCalculator,
                              @Nonnull MemoryBank memoryBank,
                              @Nonnull LearningConfig config) {
        this.stateEncoder = stateEncoder;
        this.rewardCalculator = rewardCalculator;
        this.memoryBank = memoryBank;
        this.config = config;
        this.pendingExperiences = new ArrayList<ExperienceEntry>();
        this.pendingEvents = new ArrayList<RewardCalculator.GameEvent>();
        this.previousStateVector = null;
        this.previousStateId = 0;
        this.previousActionOrdinal = 0;
        this.hasPendingDecision = false;
    }

    /**
     * Called when the DecisionEngine selects a new action. This resolves the
     * previous pending decision (if any) by computing the reward from accumulated
     * events and creating an ExperienceEntry.
     *
     * @param stateId     the discretized state ID at decision time
     * @param stateVector the 16-element continuous state vector (will be copied)
     * @param action      the action selected by the DecisionEngine
     */
    public void onDecisionMade(int stateId, @Nonnull double[] stateVector,
                               @Nonnull BotAction action) {
        // If we have a pending decision, the current state is the "next state"
        // for the previous transition.
        if (hasPendingDecision && previousStateVector != null) {
            double reward = rewardCalculator.computeTotalReward(
                    pendingEvents,
                    previousStateVector,
                    stateVector,
                    config.getDiscountFactor()
            );

            ExperienceEntry entry = new ExperienceEntry(
                    previousStateVector,
                    previousActionOrdinal,
                    reward,
                    stateVector,
                    false,  // not terminal — game is still going
                    memoryBank.getCurrentGameNumber()
            );
            entry.discretizedStateId = previousStateId;
            entry.discretizedNextStateId = stateId;

            pendingExperiences.add(entry);

            // Record outcome sign for contradiction detection
            int sign = (reward > 0.01) ? 1 : (reward < -0.01) ? -1 : 0;
            memoryBank.recordOutcomeSign(previousStateId, previousActionOrdinal, sign);

            // Update centroid for the previous state
            memoryBank.updateCentroid(previousStateId, previousStateVector);

            pendingEvents.clear();
        }

        // Store the new decision as pending
        this.previousStateVector = Arrays.copyOf(stateVector, stateVector.length);
        this.previousStateId = stateId;
        this.previousActionOrdinal = action.ordinal();
        this.hasPendingDecision = true;
    }

    /**
     * Called when a significant game event occurs (kill, damage, loot, etc.).
     * Accumulates the event for reward computation at the next decision point.
     *
     * @param eventType the event type string (e.g., "kill", "health_lost")
     * @param value     the event's numeric value
     */
    public void onGameEvent(@Nonnull String eventType, double value) {
        // Use 0 as tick timestamp — the actual tick is not critical for reward computation
        pendingEvents.add(new RewardCalculator.GameEvent(eventType, value, 0));
    }

    /**
     * Called when the game ends. Closes the final pending experience as a
     * terminal transition and returns all accumulated experiences.
     *
     * <p>The terminal experience uses the current state as both state and nextState
     * (since there is no actual next state after death/game-end). The terminal
     * reward is provided by the caller (typically win/loss/death reward).</p>
     *
     * @param terminalReward the final reward (e.g., win bonus or death penalty)
     * @return the list of all experiences from this game (caller takes ownership)
     */
    @Nonnull
    public List<ExperienceEntry> onGameEnd(double terminalReward) {
        if (hasPendingDecision && previousStateVector != null) {
            // Compute reward from any remaining events + the terminal reward
            double eventReward = rewardCalculator.computeReward(pendingEvents);
            double totalReward = eventReward + terminalReward;

            // Terminal experience: nextState = same as state (doesn't matter for TD update
            // because done=true means no bootstrap)
            ExperienceEntry entry = new ExperienceEntry(
                    previousStateVector,
                    previousActionOrdinal,
                    totalReward,
                    previousStateVector, // terminal — no meaningful next state
                    true,               // terminal flag
                    memoryBank.getCurrentGameNumber()
            );
            entry.discretizedStateId = previousStateId;
            entry.discretizedNextStateId = previousStateId; // terminal

            pendingExperiences.add(entry);

            // Record outcome sign
            int sign = (totalReward > 0.01) ? 1 : (totalReward < -0.01) ? -1 : 0;
            memoryBank.recordOutcomeSign(previousStateId, previousActionOrdinal, sign);

            // Update centroid
            memoryBank.updateCentroid(previousStateId, previousStateVector);

            pendingEvents.clear();
        }

        hasPendingDecision = false;

        // Return a copy and clear internal list
        List<ExperienceEntry> result = new ArrayList<ExperienceEntry>(pendingExperiences);
        pendingExperiences.clear();
        return result;
    }

    /**
     * Returns the current count of pending (unfinished) experiences.
     * Used for debug output.
     *
     * @return the pending experience count
     */
    public int getPendingCount() {
        return pendingExperiences.size();
    }

    /**
     * Returns the current count of pending events since the last decision.
     * Used for debug output.
     *
     * @return the pending event count
     */
    public int getPendingEventCount() {
        return pendingEvents.size();
    }

    /**
     * Returns whether there is a pending decision awaiting resolution.
     *
     * @return true if a decision is pending
     */
    public boolean hasPendingDecision() {
        return hasPendingDecision;
    }

    /**
     * Resets the recorder for a new game. Clears all pending data.
     * Called at game start.
     */
    public void reset() {
        pendingExperiences.clear();
        pendingEvents.clear();
        previousStateVector = null;
        previousStateId = 0;
        previousActionOrdinal = 0;
        hasPendingDecision = false;
    }
}
