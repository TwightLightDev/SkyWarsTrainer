// ═══════════════════════════════════════════════════════════════════
// FILE: src/main/java/org/twightlight/skywarstrainer/ai/learning/WeightAdjuster.java
// ═══════════════════════════════════════════════════════════════════

package org.twightlight.skywarstrainer.ai.learning;

import org.twightlight.skywarstrainer.ai.decision.DecisionEngine.BotAction;
import org.twightlight.skywarstrainer.util.MathUtil;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.EnumMap;
import java.util.Map;

/**
 * Translates learned Q-values from the MemoryBank into multiplier format
 * that {@link org.twightlight.skywarstrainer.ai.decision.DecisionEngine}
 * expects.
 *
 * <p>The mapping uses a tanh-based sigmoid that converts Q-values (which can be
 * positive or negative) into multipliers centered around 1.0:</p>
 * <ul>
 *   <li>Positive Q → multiplier &gt; 1.0 (boost this action)</li>
 *   <li>Negative Q → multiplier &lt; 1.0 (suppress this action)</li>
 *   <li>Zero Q → multiplier = 1.0 (no change)</li>
 * </ul>
 *
 * <p>All multipliers are clamped to [{@code minAdjustmentMultiplier},
 * {@code maxAdjustmentMultiplier}] (default [0.3, 2.5]) to ensure that
 * learning nudges behavior rather than dominating it.</p>
 *
 * <p>When the exact state is unknown (no Q-table entry), a nearest-neighbor
 * fallback searches for states that differ by exactly 1 bin in 1 dimension
 * (Hamming distance 1). This provides smooth learning generalization.</p>
 */
public class WeightAdjuster {

    private final LearningConfig config;
    private final StateEncoder stateEncoder;

    /** Cached empty adjustments map (all 1.0) returned when no data is available. */
    private static final Map<BotAction, Double> EMPTY_ADJUSTMENTS = new EnumMap<BotAction, Double>(BotAction.class);

    /**
     * Creates a new WeightAdjuster.
     *
     * @param config       the learning configuration
     * @param stateEncoder the state encoder for nearest-neighbor bin decoding
     */
    public WeightAdjuster(@Nonnull LearningConfig config, @Nonnull StateEncoder stateEncoder) {
        this.config = config;
        this.stateEncoder = stateEncoder;
    }

    /**
     * Computes weight adjustment multipliers for all actions based on the Q-values
     * for the given discretized state.
     *
     * <p>If the state is unknown, attempts a nearest-neighbor fallback. If that
     * also fails, returns an empty map (callers treat missing entries as 1.0).</p>
     *
     * @param currentStateId the discretized state ID
     * @param memoryBank     the shared memory bank
     * @return a map of BotAction → multiplier; empty map if no data available
     */
    @Nonnull
    public Map<BotAction, Double> computeAdjustments(int currentStateId,
                                                     @Nonnull MemoryBank memoryBank) {
        double[] qValues = memoryBank.getQValues(currentStateId);

        if (qValues == null) {
            // Unknown state — try nearest-neighbor fallback
            qValues = findNearestKnownState(currentStateId, memoryBank);
            if (qValues == null) {
                // Truly unknown — no adjustments
                return EMPTY_ADJUSTMENTS;
            }
        }

        double sensitivity = config.getWeightSensitivity();
        double minMult = config.getMinAdjustmentMultiplier();
        double maxMult = config.getMaxAdjustmentMultiplier();

        Map<BotAction, Double> adjustments = new EnumMap<BotAction, Double>(BotAction.class);
        BotAction[] actions = BotAction.values();

        for (int i = 0; i < actions.length && i < qValues.length; i++) {
            double q = qValues[i];
            // tanh maps [-inf, +inf] → [-1, +1]; shift to [0, 2] by adding 1.0
            double multiplier = 1.0 + Math.tanh(q * sensitivity);
            multiplier = MathUtil.clamp(multiplier, minMult, maxMult);
            adjustments.put(actions[i], multiplier);
        }

        return adjustments;
    }

    /**
     * Nearest-neighbor fallback: when the bot encounters a state it has never
     * visited, search for the most similar known state by checking all states
     * that differ by exactly 1 bin in exactly 1 dimension (Hamming distance 1).
     *
     * <p>There are at most 16 × (binsPerDimension - 1) neighbors to check
     * (e.g., 16 × 2 = 32 for 3 bins). Returns the Q-values of the neighbor
     * with the highest total visit count (most reliable).</p>
     *
     * @param stateId    the unknown state ID
     * @param memoryBank the memory bank to search
     * @return the Q-values of the best neighbor, or null if no neighbors exist
     */
    @Nullable
    private double[] findNearestKnownState(int stateId, @Nonnull MemoryBank memoryBank) {
        int bins = stateEncoder.getBinsPerDimension();
        int[] currentBins = stateEncoder.decodeToBins(stateId);

        double[] bestQValues = null;
        int bestVisitCount = -1;

        for (int dim = 0; dim < StateEncoder.STATE_VECTOR_SIZE; dim++) {
            int originalBin = currentBins[dim];

            for (int newBin = 0; newBin < bins; newBin++) {
                if (newBin == originalBin) continue;

                // Temporarily change this dimension
                currentBins[dim] = newBin;
                int neighborId = stateEncoder.encodeBins(currentBins);

                MemoryBank.QEntry neighborEntry = memoryBank.getEntry(neighborId);
                if (neighborEntry != null) {
                    int totalVisits = neighborEntry.totalVisitCount();
                    if (totalVisits > bestVisitCount) {
                        bestVisitCount = totalVisits;
                        bestQValues = neighborEntry.qValues;
                    }
                }

                // Restore the original bin
                currentBins[dim] = originalBin;
            }
        }

        return bestQValues;
    }
}