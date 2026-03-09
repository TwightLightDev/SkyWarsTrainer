package org.twightlight.skywarstrainer.ai.learning;

import org.twightlight.skywarstrainer.ai.decision.DecisionContext;
import org.twightlight.skywarstrainer.util.DebugLogger;
import org.twightlight.skywarstrainer.util.MathUtil;

import javax.annotation.Nonnull;

/**
 * Converts the rich {@link DecisionContext} object into a compact, normalized
 * {@code double[16]} feature vector suitable for Q-table lookup via discretization.
 *
 * <p>This is the "perception" layer of the learning system. All 16 features are
 * normalized to [0.0, 1.0]. The returned array is pooled (one per instance) —
 * callers must copy it if they need to persist the values.</p>
 *
 * <p>Feature vector layout:</p>
 * <pre>
 * [0]  healthFraction            [0,1]
 * [1]  equipmentScore            [0,1]
 * [2]  nearestEnemyDistance      normalized: min(dist, 80) / 80
 * [3]  visibleEnemyCount         normalized: min(count, 8) / 8
 * [4]  gameProgress              [0,1]
 * [5]  blockCount                normalized: min(blocks, 64) / 64
 * [6]  unlootedChestCount        normalized: min(chests, 10) / 10
 * [7]  onMidIsland               1.0 or 0.0
 * [8]  hasSword                  1.0 or 0.0
 * [9]  hasBow                    1.0 or 0.0
 * [10] hasGoldenApple            1.0 or 0.0
 * [11] nearVoidEdge              1.0 or 0.0
 * [12] estimatedEnemyEquipScore  [0,1] (clamped)
 * [13] timePressure              [0,1]
 * [14] enemyOnBridge             1.0 or 0.0
 * [15] alivePlayerCountNorm      normalized: min(alive, 12) / 12
 * </pre>
 */
public class StateEncoder {

    /** The dimensionality of the state vector. */
    public static final int STATE_VECTOR_SIZE = 16;

    /** Pooled output array to avoid per-call allocation. Callers must copy if persisting. */
    private final double[] pooledVector;

    /** Precomputed powers array for discretization: powers[i] = binsPerDimension^i. */
    private int[] powers;

    /** The configured bins per dimension for discretization. */
    private final int binsPerDimension;

    /**
     * Creates a new StateEncoder with the specified bins per dimension.
     *
     * @param binsPerDimension number of discrete bins per feature dimension (e.g., 3 for LOW/MED/HIGH)
     */
    public StateEncoder(int binsPerDimension) {
        this.pooledVector = new double[STATE_VECTOR_SIZE];
        this.binsPerDimension = binsPerDimension;

        // Precompute powers: powers[i] = binsPerDimension^i
        this.powers = new int[STATE_VECTOR_SIZE];
        this.powers[0] = 1;
        for (int i = 1; i < STATE_VECTOR_SIZE; i++) {
            this.powers[i] = this.powers[i - 1] * binsPerDimension;
        }
    }

    /**
     * Encodes a DecisionContext into a 16-element normalized [0,1] feature vector.
     *
     * <p>The returned array is pooled and reused across calls. If you need to
     * persist the values (e.g., for an ExperienceEntry), you MUST copy the array.</p>
     *
     * @param context the decision context to encode
     * @return the pooled 16-element vector (DO NOT STORE — copy if needed)
     */
    @Nonnull
    public double[] encode(@Nonnull DecisionContext context) {
        pooledVector[0] = sanitize(context.healthFraction);
        pooledVector[1] = sanitize(context.equipmentScore);
        pooledVector[2] = sanitize(Math.min(context.nearestEnemyDistance, 80.0) / 80.0);
        pooledVector[3] = sanitize(Math.min(context.visibleEnemyCount, 8) / 8.0);
        pooledVector[4] = sanitize(context.gameProgress);
        pooledVector[5] = sanitize(Math.min(context.blockCount, 64) / 64.0);
        pooledVector[6] = sanitize(Math.min(context.unlootedChestCount, 10) / 10.0);
        pooledVector[7] = context.onMidIsland ? 1.0 : 0.0;
        pooledVector[8] = context.hasSword ? 1.0 : 0.0;
        pooledVector[9] = context.hasBow ? 1.0 : 0.0;
        pooledVector[10] = context.hasGoldenApple ? 1.0 : 0.0;
        pooledVector[11] = context.nearVoidEdge ? 1.0 : 0.0;

        // estimatedEnemyEquipmentScore can be -1 if unknown; clamp to [0,1]
        double enemyEquip = context.estimatedEnemyEquipmentScore;
        if (enemyEquip < 0.0) {
            enemyEquip = 0.3; // Unknown → assume modestly equipped (matches DecisionContext default)
        }
        pooledVector[12] = sanitize(MathUtil.clamp(enemyEquip, 0.0, 1.0));

        pooledVector[13] = sanitize(context.timePressure);
        pooledVector[14] = context.enemyOnBridge ? 1.0 : 0.0;
        pooledVector[15] = sanitize(Math.min(context.alivePlayerCount, 12) / 12.0);

        return pooledVector;
    }

    /**
     * Discretizes a continuous [0,1] state vector into a single integer state ID
     * for Q-table lookup using mixed-radix encoding.
     *
     * <p>Each dimension is mapped to a bin in [0, binsPerDimension-1]. The state ID
     * is computed as: {@code stateId = sum(bin_i * binsPerDimension^i)}.</p>
     *
     * <p>With 3 bins and 16 dimensions, theoretical max is 3^16 = 43,046,721,
     * but in practice only a few thousand states will be visited.</p>
     *
     * @param stateVector the 16-element [0,1] vector to discretize
     * @return the discretized state ID (non-negative integer)
     */
    public int discretize(@Nonnull double[] stateVector) {
        int stateId = 0;
        for (int i = 0; i < STATE_VECTOR_SIZE; i++) {
            double value = MathUtil.clamp(stateVector[i], 0.0, 1.0);
            int bin = Math.min((int) (value * binsPerDimension), binsPerDimension - 1);
            stateId += bin * powers[i];
        }
        return stateId;
    }

    /**
     * Decodes a discretized state ID back into its bin vector.
     * Used by the nearest-neighbor fallback in WeightAdjuster.
     *
     * @param stateId the discretized state ID
     * @return an array of bin indices (length = STATE_VECTOR_SIZE)
     */
    @Nonnull
    public int[] decodeToBins(int stateId) {
        int[] bins = new int[STATE_VECTOR_SIZE];
        int remaining = stateId;
        for (int i = 0; i < STATE_VECTOR_SIZE; i++) {
            bins[i] = remaining % binsPerDimension;
            remaining /= binsPerDimension;
        }
        return bins;
    }

    /**
     * Encodes a bin vector back into a state ID. Inverse of {@link #decodeToBins(int)}.
     *
     * @param bins the bin vector (length = STATE_VECTOR_SIZE)
     * @return the state ID
     */
    public int encodeBins(@Nonnull int[] bins) {
        int stateId = 0;
        for (int i = 0; i < STATE_VECTOR_SIZE; i++) {
            stateId += bins[i] * powers[i];
        }
        return stateId;
    }

    /**
     * Returns the bins per dimension setting.
     *
     * @return bins per dimension
     */
    public int getBinsPerDimension() {
        return binsPerDimension;
    }

    /**
     * Returns the precomputed powers array.
     *
     * @return powers[i] = binsPerDimension^i
     */
    @Nonnull
    public int[] getPowers() {
        return powers;
    }

    /**
     * Sanitizes a single feature value. If NaN or Infinite, clamps to 0.5
     * (midpoint) and logs a warning. Otherwise clamps to [0.0, 1.0].
     *
     * @param value the raw feature value
     * @return a safe [0.0, 1.0] value
     */
    private static double sanitize(double value) {
        if (Double.isNaN(value) || Double.isInfinite(value)) {
            DebugLogger.logSystem("StateEncoder: NaN/Inf detected in feature value, clamping to 0.5");
            return 0.5;
        }
        return MathUtil.clamp(value, 0.0, 1.0);
    }
}
