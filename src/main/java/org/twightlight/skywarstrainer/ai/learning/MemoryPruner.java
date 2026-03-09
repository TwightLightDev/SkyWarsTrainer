// ═══════════════════════════════════════════════════════════════════
// FILE: src/main/java/org/twightlight/skywarstrainer/ai/learning/MemoryPruner.java
// ═══════════════════════════════════════════════════════════════════

package org.twightlight.skywarstrainer.ai.learning;

import org.twightlight.skywarstrainer.ai.decision.DecisionEngine.BotAction;
import org.twightlight.skywarstrainer.util.DebugLogger;

import javax.annotation.Nonnull;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

/**
 * Implements the 5-tier brain-like forgetting system for the MemoryBank.
 *
 * <p>Called after every game end by the LearningModule. The pruner runs five
 * sequential tiers of memory management to prevent unbounded growth, remove
 * stale/unreliable data, resolve contradictions, enforce capacity limits,
 * and consolidate near-duplicate entries.</p>
 *
 * <h3>Tier Overview</h3>
 * <ol>
 *   <li><b>Recency Decay:</b> Entries not accessed in N games decay toward zero.</li>
 *   <li><b>Confidence Pruning:</b> Entries with too few visits after M games are removed.</li>
 *   <li><b>Contradiction Resolution:</b> Entries where Q-value sign disagrees with
 *       recent outcomes are flipped.</li>
 *   <li><b>Capacity Limit:</b> Evict lowest-priority entries when over capacity.</li>
 *   <li><b>Consolidation:</b> Merge near-duplicate state entries (cosine similarity &gt; threshold).</li>
 * </ol>
 *
 * <p>Performance: With 50,000 entries, tiers 1–3 are O(n), tier 4 requires a sort
 * if over capacity (~5ms), tier 5 is bounded by coarse bucket sizes. Total: &lt;10ms.</p>
 */
public class MemoryPruner {

    /** Number of BotAction values, cached to avoid repeated enum queries. */
    private static final int ACTION_COUNT = BotAction.values().length;

    /** Epsilon below which all Q-values are considered zero (for recency removal). */
    private static final double QVALUE_EPSILON = 0.01;

    /** Decay multiplier base per half-life step. */
    private static final double DECAY_BASE = 0.95;

    private final LearningConfig config;

    /**
     * Creates a new MemoryPruner with parameters from the learning config.
     *
     * @param config the learning configuration
     */
    public MemoryPruner(@Nonnull LearningConfig config) {
        this.config = config;
    }

    /**
     * Runs all 5 pruning tiers on the given MemoryBank, in order.
     *
     * <p>This method modifies the MemoryBank in place: decaying Q-values,
     * removing entries, flipping contradicted Q-values, and merging duplicates.</p>
     *
     * @param memoryBank the memory bank to prune
     */
    public void prune(@Nonnull MemoryBank memoryBank) {
        long currentGame = memoryBank.getCurrentGameNumber();
        int sizeBefore = memoryBank.size();

        int removedTier1 = runRecencyDecay(memoryBank, currentGame);
        int removedTier2 = runConfidencePrune(memoryBank, currentGame);
        int flippedTier3 = runContradictionResolve(memoryBank);
        int removedTier4 = runCapacityLimit(memoryBank, currentGame);
        int mergedTier5 = runConsolidation(memoryBank);

        int sizeAfter = memoryBank.size();
        if (sizeBefore != sizeAfter) {
            DebugLogger.logSystem("MemoryPruner: %d→%d entries (decay=%d, conf=%d, flip=%d, cap=%d, merge=%d)",
                    sizeBefore, sizeAfter, removedTier1, removedTier2, flippedTier3, removedTier4, mergedTier5);
        }
    }

    // ═════════════════════════════════════════════════════════════
    //  TIER 1: RECENCY DECAY
    // ═════════════════════════════════════════════════════════════

    /**
     * Tier 1 — Recency Decay: For each entry where the time since last access
     * exceeds the half-life, multiply all Q-values by a decay factor. If ALL
     * Q-values drop below epsilon, mark for removal.
     *
     * @param memoryBank the memory bank
     * @param currentGame the current game number
     * @return the number of entries removed
     */
    private int runRecencyDecay(@Nonnull MemoryBank memoryBank, long currentGame) {
        int halfLife = config.getRecencyHalfLifeGames();
        if (halfLife <= 0) return 0;

        Map<Integer, MemoryBank.QEntry> entries = memoryBank.getEntriesMutable();
        List<Integer> toRemove = new ArrayList<Integer>();

        for (Map.Entry<Integer, MemoryBank.QEntry> mapEntry : entries.entrySet()) {
            MemoryBank.QEntry entry = mapEntry.getValue();
            long gamesSinceAccess = currentGame - entry.lastAccessedGame;

            if (gamesSinceAccess > halfLife) {
                // Compute decay steps: how many half-lives have elapsed
                double decaySteps = (double) gamesSinceAccess / halfLife;
                double decayFactor = Math.pow(DECAY_BASE, decaySteps);

                // Apply decay to all Q-values
                boolean allBelowEpsilon = true;
                for (int i = 0; i < entry.qValues.length; i++) {
                    entry.qValues[i] *= decayFactor;
                    if (Math.abs(entry.qValues[i]) >= QVALUE_EPSILON) {
                        allBelowEpsilon = false;
                    }
                }

                // If ALL Q-values are essentially zero, mark for removal
                if (allBelowEpsilon) {
                    toRemove.add(mapEntry.getKey());
                }
            }
        }

        for (Integer stateId : toRemove) {
            entries.remove(stateId);
        }

        return toRemove.size();
    }

    // ═════════════════════════════════════════════════════════════
    //  TIER 2: CONFIDENCE PRUNING
    // ═════════════════════════════════════════════════════════════

    /**
     * Tier 2 — Confidence Prune: Entries with fewer than minVisits visits across
     * ALL actions are considered unreliable. If they have existed for longer than
     * patienceGames without reaching the threshold, remove them.
     *
     * @param memoryBank the memory bank
     * @param currentGame the current game number
     * @return the number of entries removed
     */
    private int runConfidencePrune(@Nonnull MemoryBank memoryBank, long currentGame) {
        int minVisits = config.getMinVisitsForConfidence();
        int patienceGames = config.getConfidencePatienceGames();

        Map<Integer, MemoryBank.QEntry> entries = memoryBank.getEntriesMutable();
        List<Integer> toRemove = new ArrayList<Integer>();

        for (Map.Entry<Integer, MemoryBank.QEntry> mapEntry : entries.entrySet()) {
            MemoryBank.QEntry entry = mapEntry.getValue();

            // Check if max visit count across all actions is below threshold
            if (entry.maxVisitCount() < minVisits) {
                long age = currentGame - entry.createdGame;
                if (age > patienceGames) {
                    toRemove.add(mapEntry.getKey());
                }
            }
        }

        for (Integer stateId : toRemove) {
            entries.remove(stateId);
        }

        return toRemove.size();
    }

    // ═════════════════════════════════════════════════════════════
    //  TIER 3: CONTRADICTION RESOLUTION
    // ═════════════════════════════════════════════════════════════

    /**
     * Tier 3 — Contradiction Resolve: For each entry and each action, if the
     * Q-value sign (positive = "good") disagrees with the majority of recent
     * outcomes, flip the Q-value.
     *
     * <p>The recentOutcomeSigns circular buffer stores packed values:
     * {@code (actionOrdinal << 2) | (sign + 1)} where sign+1 maps
     * {-1, 0, +1} → {0, 1, 2}.</p>
     *
     * @param memoryBank the memory bank
     * @return the number of Q-values flipped
     */
    private int runContradictionResolve(@Nonnull MemoryBank memoryBank) {
        double threshold = config.getContradictionThreshold();
        int flipped = 0;

        for (MemoryBank.QEntry entry : memoryBank.getEntriesMutable().values()) {
            for (int actionOrd = 0; actionOrd < entry.qValues.length; actionOrd++) {
                double qValue = entry.qValues[actionOrd];
                if (Math.abs(qValue) < QVALUE_EPSILON) continue; // skip zero Q-values

                // Count recent outcomes for this specific action
                int positiveCount = 0;
                int negativeCount = 0;
                int totalNonZero = 0;

                for (int packed : entry.recentOutcomeSigns) {
                    int storedAction = packed >> 2;
                    int storedSignPlusOne = packed & 0x3; // bottom 2 bits

                    if (storedAction == actionOrd) {
                        if (storedSignPlusOne == 2) {      // original sign = +1
                            positiveCount++;
                            totalNonZero++;
                        } else if (storedSignPlusOne == 0) { // original sign = -1
                            negativeCount++;
                            totalNonZero++;
                        }
                        // storedSignPlusOne == 1 means sign = 0, skip
                    }
                }

                if (totalNonZero < 3) continue; // not enough data to judge

                double negativeFraction = (double) negativeCount / totalNonZero;
                double positiveFraction = (double) positiveCount / totalNonZero;

                // Q-value positive but recent outcomes mostly negative
                if (qValue > 0 && negativeFraction > threshold) {
                    entry.qValues[actionOrd] = -0.5 * Math.abs(qValue);
                    flipped++;
                }
                // Q-value negative but recent outcomes mostly positive
                else if (qValue < 0 && positiveFraction > threshold) {
                    entry.qValues[actionOrd] = 0.5 * Math.abs(qValue);
                    flipped++;
                }
            }
        }

        return flipped;
    }

    // ═════════════════════════════════════════════════════════════
    //  TIER 4: CAPACITY LIMIT
    // ═════════════════════════════════════════════════════════════

    /**
     * Tier 4 — Capacity Limit: If the MemoryBank exceeds max capacity, evict
     * the lowest-priority entries. Priority is computed as:
     * {@code max(|qValues|) × recencyWeight × (max(visitCounts) + 1) / 10.0}
     *
     * @param memoryBank the memory bank
     * @param currentGame the current game number
     * @return the number of entries removed
     */
    private int runCapacityLimit(@Nonnull MemoryBank memoryBank, long currentGame) {
        int maxCapacity = config.getMaxEntries();
        int halfLife = config.getRecencyHalfLifeGames();

        Map<Integer, MemoryBank.QEntry> entries = memoryBank.getEntriesMutable();
        if (entries.size() <= maxCapacity) return 0;

        int toEvict = entries.size() - maxCapacity;

        // Compute priority for each entry
        List<PrioritizedEntry> prioritized = new ArrayList<PrioritizedEntry>(entries.size());
        for (Map.Entry<Integer, MemoryBank.QEntry> mapEntry : entries.entrySet()) {
            MemoryBank.QEntry entry = mapEntry.getValue();

            double maxAbsQ = 0.0;
            for (double q : entry.qValues) {
                double absQ = Math.abs(q);
                if (absQ > maxAbsQ) maxAbsQ = absQ;
            }

            double recencyWeight = 1.0 / (1.0 + (double) (currentGame - entry.lastAccessedGame) / halfLife);
            double priority = maxAbsQ * recencyWeight * (entry.maxVisitCount() + 1) / 10.0;

            prioritized.add(new PrioritizedEntry(mapEntry.getKey(), priority));
        }

        // Sort ascending by priority (lowest first)
        Collections.sort(prioritized, new Comparator<PrioritizedEntry>() {
            @Override
            public int compare(PrioritizedEntry a, PrioritizedEntry b) {
                return Double.compare(a.priority, b.priority);
            }
        });

        // Remove lowest-priority entries
        int removed = 0;
        for (int i = 0; i < toEvict && i < prioritized.size(); i++) {
            entries.remove(prioritized.get(i).stateId);
            removed++;
        }

        return removed;
    }

    // ═════════════════════════════════════════════════════════════
    //  TIER 5: CONSOLIDATION
    // ═════════════════════════════════════════════════════════════

    /**
     * Tier 5 — Consolidation: Merge near-duplicate state entries that have very
     * similar centroids (cosine similarity &gt; threshold). Only entries within
     * the same coarse bucket are compared, keeping the comparison count bounded.
     *
     * <p>Coarse bucket: {@code stateId / (binsPerDimension^4)}, which groups by
     * the first 12 dimensions and ignores the last 4. Typical bucket has &lt;20 entries.</p>
     *
     * @param memoryBank the memory bank
     * @return the number of entries merged (removed)
     */
    private int runConsolidation(@Nonnull MemoryBank memoryBank) {
        double similarityThreshold = config.getConsolidationSimilarity();
        int bins = config.getBinsPerDimension();

        // binsPerDimension^4 — the divisor to create coarse buckets
        int bucketDivisor = bins * bins * bins * bins;
        if (bucketDivisor <= 0) bucketDivisor = 1;

        Map<Integer, MemoryBank.QEntry> entries = memoryBank.getEntriesMutable();

        // Group entries into coarse buckets
        Map<Integer, List<Integer>> buckets = new java.util.HashMap<Integer, List<Integer>>();
        for (Integer stateId : entries.keySet()) {
            int bucketKey = stateId / bucketDivisor;
            List<Integer> bucket = buckets.get(bucketKey);
            if (bucket == null) {
                bucket = new ArrayList<Integer>();
                buckets.put(bucketKey, bucket);
            }
            bucket.add(stateId);
        }

        int merged = 0;

        for (List<Integer> bucket : buckets.values()) {
            if (bucket.size() < 2) continue;

            // Compare all pairs within the bucket
            // Use index-based iteration with removal tracking
            boolean[] removed = new boolean[bucket.size()];

            for (int i = 0; i < bucket.size(); i++) {
                if (removed[i]) continue;

                int stateIdA = bucket.get(i);
                MemoryBank.QEntry entryA = entries.get(stateIdA);
                if (entryA == null || entryA.centroidSampleCount == 0) continue;

                for (int j = i + 1; j < bucket.size(); j++) {
                    if (removed[j]) continue;

                    int stateIdB = bucket.get(j);
                    MemoryBank.QEntry entryB = entries.get(stateIdB);
                    if (entryB == null || entryB.centroidSampleCount == 0) continue;

                    double similarity = cosineSimilarity(entryA.stateCentroid, entryB.stateCentroid);
                    if (similarity > similarityThreshold) {
                        // Merge B into A (A has more visits? pick the one with more visits to keep)
                        int totalA = entryA.totalVisitCount();
                        int totalB = entryB.totalVisitCount();

                        if (totalA >= totalB) {
                            memoryBank.mergeEntries(stateIdA, stateIdB);
                            removed[j] = true;
                        } else {
                            memoryBank.mergeEntries(stateIdB, stateIdA);
                            removed[i] = true;
                            // entryA was removed, stop comparing with it
                            break;
                        }
                        merged++;
                    }
                }
            }
        }

        return merged;
    }

    // ═════════════════════════════════════════════════════════════
    //  UTILITY METHODS
    // ═════════════════════════════════════════════════════════════

    /**
     * Computes the cosine similarity between two vectors.
     *
     * <p>Returns a value in [-1.0, 1.0] where 1.0 means identical direction.
     * If either vector has zero magnitude, returns 0.0.</p>
     *
     * @param a the first vector
     * @param b the second vector
     * @return the cosine similarity
     */
    static double cosineSimilarity(@Nonnull double[] a, @Nonnull double[] b) {
        int len = Math.min(a.length, b.length);
        double dotProduct = 0.0;
        double normA = 0.0;
        double normB = 0.0;

        for (int i = 0; i < len; i++) {
            dotProduct += a[i] * b[i];
            normA += a[i] * a[i];
            normB += b[i] * b[i];
        }

        double denominator = Math.sqrt(normA) * Math.sqrt(normB);
        if (denominator < 1e-10) return 0.0;

        return dotProduct / denominator;
    }

    /**
     * A simple holder pairing a state ID with a computed priority score,
     * used for sorting during capacity eviction.
     */
    private static final class PrioritizedEntry {
        final int stateId;
        final double priority;

        PrioritizedEntry(int stateId, double priority) {
            this.stateId = stateId;
            this.priority = priority;
        }
    }
}