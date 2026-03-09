package org.twightlight.skywarstrainer.ai.learning;

import org.twightlight.skywarstrainer.ai.decision.DecisionEngine.BotAction;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

/**
 * The persistent knowledge store for the reinforcement learning system.
 *
 * <p>Holds a Q-table mapping (discretizedStateId, actionOrdinal) → Q-value,
 * along with rich metadata per entry for the 5-tier memory pruning system:
 * visit counts, recency timestamps, contradiction detection via outcome signs,
 * and continuous-state centroids for consolidation.</p>
 *
 * <p>The MemoryBank is shared across all bot instances on the server as a
 * singleton held by the plugin main class. Knowledge learned by one bot
 * immediately benefits all other bots.</p>
 *
 * <p>Maximum capacity is configurable (default 50,000 entries). The
 * {@link MemoryPruner} manages eviction when capacity is reached.</p>
 */
public class MemoryBank {

    /** The number of possible actions (BotAction enum size). */
    private static final int ACTION_COUNT = BotAction.values().length;

    /** Q-table: stateId → QEntry. */
    private final Map<Integer, QEntry> qTable;

    /** The maximum number of entries allowed. */
    private final int maxCapacity;

    /** Size of the circular outcome buffer for contradiction detection. */
    private final int contradictionLookback;

    /** The current game number, incremented each game. */
    private long currentGameNumber;

    /**
     * Creates a new MemoryBank with parameters from the learning config.
     *
     * @param config the learning configuration
     */
    public MemoryBank(@Nonnull LearningConfig config) {
        this.maxCapacity = config.getMaxEntries();
        this.contradictionLookback = config.getContradictionLookback();
        this.qTable = new HashMap<Integer, QEntry>();
        this.currentGameNumber = 0;
    }

    // ═════════════════════════════════════════════════════════════
    //  READ OPERATIONS
    // ═════════════════════════════════════════════════════════════

    /**
     * Returns the Q-value for a specific (state, action) pair.
     * Returns 0.0 if the state is unknown.
     *
     * @param stateId       the discretized state ID
     * @param actionOrdinal the action ordinal
     * @return the Q-value, or 0.0 if not found
     */
    public double getQValue(int stateId, int actionOrdinal) {
        QEntry entry = qTable.get(stateId);
        if (entry == null) return 0.0;
        if (actionOrdinal < 0 || actionOrdinal >= entry.qValues.length) return 0.0;
        entry.lastAccessedGame = currentGameNumber;
        return entry.qValues[actionOrdinal];
    }

    /**
     * Returns the full Q-value array for a state. Returns null if the state
     * is unknown. The returned array is the LIVE array — do not modify it
     * externally. Copy if needed.
     *
     * @param stateId the discretized state ID
     * @return the Q-value array (one per BotAction), or null if unknown
     */
    @Nullable
    public double[] getQValues(int stateId) {
        QEntry entry = qTable.get(stateId);
        if (entry == null) return null;
        entry.lastAccessedGame = currentGameNumber;
        return entry.qValues;
    }

    /**
     * Returns the QEntry for a state. Returns null if unknown.
     *
     * @param stateId the discretized state ID
     * @return the QEntry, or null
     */
    @Nullable
    public QEntry getEntry(int stateId) {
        return qTable.get(stateId);
    }

    // ═════════════════════════════════════════════════════════════
    //  WRITE OPERATIONS
    // ═════════════════════════════════════════════════════════════

    /**
     * Sets the Q-value for a (state, action) pair to an absolute value.
     * Creates the entry if it doesn't exist.
     *
     * @param stateId       the discretized state ID
     * @param actionOrdinal the action ordinal
     * @param newValue      the new Q-value
     */
    public void updateQValue(int stateId, int actionOrdinal, double newValue) {
        QEntry entry = getOrCreateEntry(stateId);
        if (actionOrdinal >= 0 && actionOrdinal < entry.qValues.length) {
            entry.qValues[actionOrdinal] = newValue;
            entry.lastAccessedGame = currentGameNumber;
        }
    }

    /**
     * Adds a delta to the current Q-value for a (state, action) pair.
     * Creates the entry if it doesn't exist (starting from Q=0).
     *
     * <p>This is the method used by eligibility traces to propagate
     * TD-errors additively across multiple state-action pairs.</p>
     *
     * @param stateId       the discretized state ID
     * @param actionOrdinal the action ordinal
     * @param delta         the value to add to the current Q-value
     */
    public void updateQValueAdditive(int stateId, int actionOrdinal, double delta) {
        QEntry entry = getOrCreateEntry(stateId);
        if (actionOrdinal >= 0 && actionOrdinal < entry.qValues.length) {
            entry.qValues[actionOrdinal] += delta;
            entry.lastAccessedGame = currentGameNumber;
        }
    }

    /**
     * Increments the visit count for a (state, action) pair.
     *
     * @param stateId       the discretized state ID
     * @param actionOrdinal the action ordinal
     */
    public void incrementVisit(int stateId, int actionOrdinal) {
        QEntry entry = getOrCreateEntry(stateId);
        if (actionOrdinal >= 0 && actionOrdinal < entry.visitCounts.length) {
            entry.visitCounts[actionOrdinal]++;
        }
    }

    /**
     * Records an outcome sign (+1, -1, or 0) for a (state, action) pair
     * into the circular contradiction-detection buffer.
     *
     * @param stateId       the discretized state ID
     * @param actionOrdinal the action ordinal
     * @param sign          +1 for positive reward, -1 for negative, 0 for zero
     */
    public void recordOutcomeSign(int stateId, int actionOrdinal, int sign) {
        QEntry entry = getOrCreateEntry(stateId);
        // Store in circular buffer — we pack (actionOrdinal, sign) together.
        // The circular buffer tracks overall outcome signs per entry (not per-action).
        // For per-action contradiction detection, we index by action in the pruner
        // using the raw recentOutcomeSigns + knowledge of which action was taken.
        //
        // Simplified approach: store packed (actionOrdinal << 2 | (sign+1)) to fit in int
        int packed = (actionOrdinal << 2) | (sign + 1); // sign+1 maps {-1,0,1} → {0,1,2}
        entry.recentOutcomeSigns[entry.recentOutcomeHead] = packed;
        entry.recentOutcomeHead = (entry.recentOutcomeHead + 1) % entry.recentOutcomeSigns.length;
    }

    /**
     * Updates the running centroid for a state by incorporating a new continuous
     * state vector via incremental mean.
     *
     * @param stateId     the discretized state ID
     * @param stateVector the 16-element continuous state vector
     */
    public void updateCentroid(int stateId, @Nonnull double[] stateVector) {
        QEntry entry = getOrCreateEntry(stateId);
        entry.centroidSampleCount++;
        for (int i = 0; i < entry.stateCentroid.length && i < stateVector.length; i++) {
            entry.stateCentroid[i] += (stateVector[i] - entry.stateCentroid[i]) / entry.centroidSampleCount;
        }
    }

    // ═════════════════════════════════════════════════════════════
    //  PRUNING INTERFACE
    // ═════════════════════════════════════════════════════════════

    /**
     * Returns an unmodifiable view of all entries in the Q-table.
     * Used by the MemoryPruner to iterate and evaluate entries.
     *
     * @return unmodifiable map of stateId → QEntry
     */
    @Nonnull
    public Map<Integer, QEntry> getAllEntries() {
        return Collections.unmodifiableMap(qTable);
    }

    /**
     * Returns the live (mutable) entries map. Used only by MemoryPruner
     * which needs to remove/merge entries. Package-private access.
     *
     * @return the mutable Q-table map
     */
    public Map<Integer, QEntry> getEntriesMutable() {
        return qTable;
    }

    /**
     * Removes an entry from the Q-table.
     *
     * @param stateId the state ID to remove
     */
    public void removeEntry(int stateId) {
        qTable.remove(stateId);
    }

    /**
     * Merges two entries by combining their data into the "keep" entry
     * and removing the "remove" entry.
     *
     * @param stateIdKeep   the state ID to keep (receives merged data)
     * @param stateIdRemove the state ID to remove (data merged into keep, then deleted)
     */
    public void mergeEntries(int stateIdKeep, int stateIdRemove) {
        QEntry keep = qTable.get(stateIdKeep);
        QEntry remove = qTable.get(stateIdRemove);
        if (keep == null || remove == null) return;

        int keepTotal = getTotalVisits(keep);
        int removeTotal = getTotalVisits(remove);
        int combinedTotal = keepTotal + removeTotal;

        if (combinedTotal > 0) {
            for (int i = 0; i < ACTION_COUNT; i++) {
                keep.qValues[i] = (keep.qValues[i] * keepTotal + remove.qValues[i] * removeTotal) / combinedTotal;
                keep.visitCounts[i] = keep.visitCounts[i] + remove.visitCounts[i];
            }
        }

        // Merge centroids (weighted average by sample count)
        int keepSamples = keep.centroidSampleCount;
        int removeSamples = remove.centroidSampleCount;
        int totalSamples = keepSamples + removeSamples;
        if (totalSamples > 0) {
            for (int i = 0; i < keep.stateCentroid.length; i++) {
                keep.stateCentroid[i] = (keep.stateCentroid[i] * keepSamples
                        + remove.stateCentroid[i] * removeSamples) / totalSamples;
            }
            keep.centroidSampleCount = totalSamples;
        }

        // Keep the most recent access and earliest creation
        keep.lastAccessedGame = Math.max(keep.lastAccessedGame, remove.lastAccessedGame);
        keep.createdGame = Math.min(keep.createdGame, remove.createdGame);

        qTable.remove(stateIdRemove);
    }

    // ═════════════════════════════════════════════════════════════
    //  LIFECYCLE
    // ═════════════════════════════════════════════════════════════

    /**
     * Called at the start of each game. Increments the game counter.
     */
    public void onGameStart() {
        currentGameNumber++;
    }

    /**
     * Called at the end of each game. Currently a no-op; pruning is triggered
     * separately by the LearningModule.
     */
    public void onGameEnd() {
        // Pruning is triggered by LearningModule, not here
    }

    /**
     * Returns the current game number.
     *
     * @return the game number
     */
    public long getCurrentGameNumber() {
        return currentGameNumber;
    }

    /**
     * Sets the current game number. Used when loading from disk.
     *
     * @param gameNumber the game number to set
     */
    public void setCurrentGameNumber(long gameNumber) {
        this.currentGameNumber = gameNumber;
    }

    /**
     * Returns the number of entries in the Q-table.
     *
     * @return the entry count
     */
    public int size() {
        return qTable.size();
    }

    /**
     * Returns the maximum capacity.
     *
     * @return the max entries allowed
     */
    public int getMaxCapacity() {
        return maxCapacity;
    }

    /**
     * Returns the contradiction lookback size (for QEntry circular buffers).
     *
     * @return the lookback size
     */
    public int getContradictionLookback() {
        return contradictionLookback;
    }

    // ═════════════════════════════════════════════════════════════
    //  INTERNAL
    // ═════════════════════════════════════════════════════════════

    /**
     * Returns the existing QEntry for a state or creates a new one if absent.
     *
     * @param stateId the discretized state ID
     * @return the QEntry (never null)
     */
    @Nonnull
    private QEntry getOrCreateEntry(int stateId) {
        QEntry entry = qTable.get(stateId);
        if (entry == null) {
            entry = new QEntry(ACTION_COUNT, contradictionLookback, currentGameNumber);
            qTable.put(stateId, entry);
        }
        return entry;
    }

    /**
     * Computes the total visit count across all actions for an entry.
     *
     * @param entry the QEntry
     * @return the sum of all visitCounts
     */
    static int getTotalVisits(@Nonnull QEntry entry) {
        int total = 0;
        for (int v : entry.visitCounts) {
            total += v;
        }
        return total;
    }

    // ═════════════════════════════════════════════════════════════
    //  INNER CLASS: QEntry
    // ═════════════════════════════════════════════════════════════

    /**
     * A single entry in the Q-table, storing Q-values for all actions along with
     * metadata for the 5-tier memory pruning system.
     */
    public static class QEntry {

        /** Q-values, one per BotAction (indexed by ordinal). */
        public double[] qValues;

        /** Visit counts, one per BotAction. */
        public int[] visitCounts;

        /**
         * Circular buffer of recent outcome signs for contradiction detection.
         * Each element is packed as: (actionOrdinal << 2) | (sign + 1).
         * sign+1 maps {-1, 0, +1} → {0, 1, 2}.
         */
        public int[] recentOutcomeSigns;

        /** Write position in the recentOutcomeSigns circular buffer. */
        public int recentOutcomeHead;

        /** Game number when this entry was last accessed (read or written). */
        public long lastAccessedGame;

        /** Game number when this entry was first created. */
        public long createdGame;

        /**
         * Running average of all continuous state vectors that mapped to this
         * discrete state ID. Used by consolidation (Tier 5) to compute cosine
         * similarity between entries.
         */
        public double[] stateCentroid;

        /** Number of state vectors averaged into the centroid. */
        public int centroidSampleCount;

        /**
         * Creates a new QEntry with zeroed Q-values and metadata.
         *
         * @param actionCount         the number of actions (BotAction enum size)
         * @param contradictionLookback the size of the outcome circular buffer
         * @param currentGameNumber   the current game number (for createdGame)
         */
        public QEntry(int actionCount, int contradictionLookback, long currentGameNumber) {
            this.qValues = new double[actionCount];
            this.visitCounts = new int[actionCount];
            this.recentOutcomeSigns = new int[contradictionLookback];
            this.recentOutcomeHead = 0;
            this.lastAccessedGame = currentGameNumber;
            this.createdGame = currentGameNumber;
            this.stateCentroid = new double[StateEncoder.STATE_VECTOR_SIZE];
            this.centroidSampleCount = 0;
        }

        /**
         * Creates a QEntry with pre-specified values. Used for deserialization.
         *
         * @param qValues              Q-value array
         * @param visitCounts          visit count array
         * @param recentOutcomeSigns   outcome signs circular buffer
         * @param recentOutcomeHead    write head position
         * @param lastAccessedGame     last accessed game number
         * @param createdGame          created game number
         * @param stateCentroid        state centroid vector
         * @param centroidSampleCount  centroid sample count
         */
        public QEntry(double[] qValues, int[] visitCounts, int[] recentOutcomeSigns,
                      int recentOutcomeHead, long lastAccessedGame, long createdGame,
                      double[] stateCentroid, int centroidSampleCount) {
            this.qValues = qValues;
            this.visitCounts = visitCounts;
            this.recentOutcomeSigns = recentOutcomeSigns;
            this.recentOutcomeHead = recentOutcomeHead;
            this.lastAccessedGame = lastAccessedGame;
            this.createdGame = createdGame;
            this.stateCentroid = stateCentroid;
            this.centroidSampleCount = centroidSampleCount;
        }

        /**
         * Returns the maximum Q-value across all actions.
         *
         * @return the maximum Q-value
         */
        public double maxQValue() {
            double max = Double.NEGATIVE_INFINITY;
            for (double q : qValues) {
                if (q > max) max = q;
            }
            return max;
        }

        /**
         * Returns the action ordinal with the highest Q-value.
         *
         * @return the argmax action ordinal
         */
        public int argMaxAction() {
            int bestAction = 0;
            double bestValue = Double.NEGATIVE_INFINITY;
            for (int i = 0; i < qValues.length; i++) {
                if (qValues[i] > bestValue) {
                    bestValue = qValues[i];
                    bestAction = i;
                }
            }
            return bestAction;
        }

        /**
         * Returns the maximum visit count across all actions.
         *
         * @return the maximum visit count
         */
        public int maxVisitCount() {
            int max = 0;
            for (int v : visitCounts) {
                if (v > max) max = v;
            }
            return max;
        }

        /**
         * Returns the total visit count across all actions.
         *
         * @return the sum of all visit counts
         */
        public int totalVisitCount() {
            int total = 0;
            for (int v : visitCounts) total += v;
            return total;
        }

        /**
         * Deep-copies this QEntry. Used for snapshot-based serialization.
         *
         * @return a new QEntry with copied arrays
         */
        public QEntry deepCopy() {
            return new QEntry(
                    Arrays.copyOf(qValues, qValues.length),
                    Arrays.copyOf(visitCounts, visitCounts.length),
                    Arrays.copyOf(recentOutcomeSigns, recentOutcomeSigns.length),
                    recentOutcomeHead,
                    lastAccessedGame,
                    createdGame,
                    Arrays.copyOf(stateCentroid, stateCentroid.length),
                    centroidSampleCount
            );
        }

        @Override
        public String toString() {
            return "QEntry{visits=" + totalVisitCount()
                    + ", maxQ=" + String.format("%.3f", maxQValue())
                    + ", lastGame=" + lastAccessedGame
                    + ", created=" + createdGame
                    + ", centroidSamples=" + centroidSampleCount + "}";
        }
    }
}
