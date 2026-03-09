package org.twightlight.skywarstrainer.ai.learning;

import javax.annotation.Nonnull;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;

/**
 * Maintains per-game decaying eligibility traces for TD(λ) credit assignment.
 *
 * <p>Eligibility traces solve the delayed credit assignment problem: when a reward
 * arrives many decisions after the causal action, the trace propagates the update
 * backward to all recently visited state-action pairs, weighted by how recently
 * they were visited.</p>
 *
 * <p>This implementation uses <b>replacing traces</b>: when a state-action pair is
 * revisited, its trace is set to 1.0 (not accumulated), preventing instability
 * from trace accumulation in revisited states.</p>
 *
 * <p>Traces are per-game only: reset at game start, applied within a game, and
 * cleared at game end. The replay buffer (Phase 3) handles cross-game learning
 * using standard TD(0) updates — traces and replay are complementary.</p>
 *
 * <h3>Key Storage</h3>
 * <p>Keys are packed as {@code (stateId, actionOrdinal)} into a single long:
 * upper 32 bits = stateId, lower 32 bits = actionOrdinal.</p>
 */
public class EligibilityTraceTable {

    /** Map from packed (stateId, actionOrdinal) → trace value. */
    private final Map<Long, Double> traces;

    /** Discount factor gamma. */
    private final double gamma;

    /** Trace decay parameter lambda. */
    private final double lambda;

    /** Traces below this threshold are pruned to bound map size. */
    private final double traceThreshold;

    /**
     * Creates a new EligibilityTraceTable with parameters from config.
     *
     * @param config the learning configuration
     */
    public EligibilityTraceTable(@Nonnull LearningConfig config) {
        this.traces = new HashMap<Long, Double>();
        this.gamma = config.getDiscountFactor();
        this.lambda = config.getLambda();
        this.traceThreshold = config.getTracePruneThreshold();
    }

    /**
     * Updates traces when a new decision is made. All existing traces are decayed
     * by gamma * lambda, entries below threshold are pruned, and the current
     * (stateId, actionOrdinal) trace is set to 1.0 (replacing traces).
     *
     * @param stateId       the discretized state ID
     * @param actionOrdinal the action ordinal
     */
    public void update(int stateId, int actionOrdinal) {
        double decayFactor = gamma * lambda;

        // Decay all existing traces and prune below threshold
        Iterator<Map.Entry<Long, Double>> it = traces.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<Long, Double> entry = it.next();
            double newValue = entry.getValue() * decayFactor;
            if (newValue < traceThreshold) {
                it.remove();
            } else {
                entry.setValue(newValue);
            }
        }

        // Set current state-action trace to 1.0 (replacing traces)
        long key = packKey(stateId, actionOrdinal);
        traces.put(key, 1.0);
    }

    /**
     * Applies a TD-error to all traced state-action pairs by updating
     * the Q-values in the MemoryBank additively.
     *
     * <p>For each (s, a) with trace e(s,a):
     * {@code Q(s, a) += learningRate * tdError * e(s, a)}</p>
     *
     * @param tdError      the TD-error delta
     * @param learningRate the effective learning rate
     * @param memoryBank   the memory bank to update
     */
    public void applyTDError(double tdError, double learningRate,
                             @Nonnull MemoryBank memoryBank) {
        for (Map.Entry<Long, Double> entry : traces.entrySet()) {
            long key = entry.getKey();
            double traceValue = entry.getValue();

            int stateId = unpackStateId(key);
            int actionOrdinal = unpackAction(key);

            double delta = learningRate * tdError * traceValue;
            memoryBank.updateQValueAdditive(stateId, actionOrdinal, delta);
        }
    }

    /**
     * Resets all traces. Called at game start and game end.
     */
    public void reset() {
        traces.clear();
    }

    /**
     * Returns the current number of active trace entries.
     * Used for debug output.
     *
     * @return the trace count
     */
    public int size() {
        return traces.size();
    }

    // ── Pack/Unpack helpers ──

    /**
     * Packs a (stateId, actionOrdinal) pair into a single long key.
     * Upper 32 bits = stateId, lower 32 bits = actionOrdinal.
     *
     * @param stateId       the state ID
     * @param actionOrdinal the action ordinal
     * @return the packed key
     */
    static long packKey(int stateId, int actionOrdinal) {
        return ((long) stateId << 32) | (actionOrdinal & 0xFFFFFFFFL);
    }

    /**
     * Unpacks the state ID from a packed key.
     *
     * @param key the packed key
     * @return the state ID
     */
    static int unpackStateId(long key) {
        return (int) (key >> 32);
    }

    /**
     * Unpacks the action ordinal from a packed key.
     *
     * @param key the packed key
     * @return the action ordinal
     */
    static int unpackAction(long key) {
        return (int) (key & 0xFFFFFFFFL);
    }
}
