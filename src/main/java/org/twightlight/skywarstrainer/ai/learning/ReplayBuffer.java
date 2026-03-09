package org.twightlight.skywarstrainer.ai.learning;

import org.twightlight.skywarstrainer.util.RandomUtil;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * A fixed-capacity circular buffer of experience tuples with prioritized
 * sampling for experience replay. Shared across all bot instances.
 *
 * <p>Implements Prioritized Experience Replay (Schaul et al., 2016) using a
 * sum-tree data structure for O(log N) proportional sampling. Experiences with
 * higher absolute TD-error (more "surprising") are sampled more frequently.</p>
 *
 * <p>Importance-sampling weights correct for the bias introduced by non-uniform
 * sampling. The beta parameter anneals from betaStart toward 1.0 over the first
 * N games, gradually increasing correction strength.</p>
 *
 * <h3>Thread Safety</h3>
 * <p>The buffer is accessed by multiple bot instances, but all access occurs on
 * the main server thread (Spigot is single-threaded for game logic). The only
 * concurrent risk is during async save, which must use a snapshot. No internal
 * synchronization is needed.</p>
 */
public class ReplayBuffer {

    /** The circular array of experience entries. */
    private final ReplayEntry[] buffer;

    /** Next write position in the circular buffer. */
    private int head;

    /** Current number of valid entries. */
    private int size;

    /** Maximum capacity. */
    private final int capacity;

    // ── Sum-tree for O(log N) prioritized sampling ──
    // Binary tree where leaves correspond to buffer entries.
    // Internal nodes store sum of children (for proportional sampling).
    // Tree size = 2 * capacity (index 0 unused, root at 1, leaves at [capacity, 2*capacity-1]).

    /** Sum-tree: internal nodes store sum of children's priorities. */
    private final double[] sumTree;

    /** Min-tree: internal nodes store min of children's priorities (for IS weight normalization). */
    private final double[] minTree;

    // ── Hyperparameters ──
    private final double alpha;
    private double betaCurrent;
    private final double betaStart;
    private final double betaEnd;
    private final int betaAnnealGames;
    private final double epsilon;
    private final int maxExperienceAgeGames;

    /** The maximum priority ever seen. New experiences get this priority. */
    private double maxPriorityEverSeen;

    /** Current game number (for staleness detection and beta annealing). */
    private long currentGameNumber;

    /** Total games processed (for beta annealing). */
    private long totalGamesProcessed;

    /**
     * Creates a new ReplayBuffer with parameters from the learning config.
     *
     * @param config the learning configuration
     */
    public ReplayBuffer(@Nonnull LearningConfig config) {
        this.capacity = config.getBufferCapacity();
        this.buffer = new ReplayEntry[capacity];
        this.head = 0;
        this.size = 0;

        // Sum-tree and min-tree: array-based binary tree with capacity leaves
        // Total nodes = 2 * capacity. Index 0 unused. Root at 1.
        // Leaves at indices [capacity, 2*capacity-1].
        int treeSize = 2 * capacity;
        this.sumTree = new double[treeSize];
        this.minTree = new double[treeSize];
        // Initialize min-tree to infinity
        for (int i = 0; i < treeSize; i++) {
            minTree[i] = Double.MAX_VALUE;
        }

        this.alpha = config.getPriorityAlpha();
        this.betaStart = config.getImportanceSamplingBetaStart();
        this.betaEnd = config.getImportanceSamplingBetaEnd();
        this.betaCurrent = betaStart;
        this.betaAnnealGames = config.getBetaAnnealGames();
        this.epsilon = config.getPriorityEpsilon();
        this.maxExperienceAgeGames = config.getMaxExperienceAgeGames();
        this.maxPriorityEverSeen = 1.0; // Initial default
        this.currentGameNumber = 0;
        this.totalGamesProcessed = 0;
    }

    // ═════════════════════════════════════════════════════════════
    //  ADD
    // ═════════════════════════════════════════════════════════════

    /**
     * Adds a new experience to the buffer. If the buffer is full, the oldest
     * entry is overwritten. New entries receive the maximum priority ever seen,
     * guaranteeing they will be sampled at least once.
     *
     * @param entry the replay entry to add
     */
    public void add(@Nonnull ReplayEntry entry) {
        // Assign max priority so new experiences are guaranteed to be sampled
        entry.tdErrorPriority = maxPriorityEverSeen;

        buffer[head] = entry;
        updateTree(head, Math.pow(maxPriorityEverSeen, alpha));

        head = (head + 1) % capacity;
        if (size < capacity) {
            size++;
        }
    }

    // ═════════════════════════════════════════════════════════════
    //  SAMPLE
    // ═════════════════════════════════════════════════════════════

    /**
     * Samples a mini-batch of experiences with prioritized selection.
     *
     * <p>Each sample is drawn proportionally to its priority^alpha. Importance-
     * sampling weights are computed and normalized so the maximum weight is 1.0.</p>
     *
     * @param batchSize the number of experiences to sample
     * @return the sampled batch with entries, indices, and IS weights; or null if buffer is empty
     */
    @Nullable
    public SampledBatch sample(int batchSize) {
        if (size == 0) return null;
        if (batchSize > size) batchSize = size;

        ReplayEntry[] entries = new ReplayEntry[batchSize];
        int[] indices = new int[batchSize];
        double[] importanceWeights = new double[batchSize];

        double totalPriority = sumTree[1]; // Root of sum-tree
        if (totalPriority <= 0.0) {
            // Fallback: uniform random sampling
            return sampleUniform(batchSize);
        }

        double minPriority = minTree[1]; // Root of min-tree
        // Max IS weight (for normalization): w_max = (1 / (N * P_min))^beta
        double pMin = minPriority / totalPriority;
        if (pMin <= 0.0) pMin = 1e-10;
        double maxWeight = Math.pow(1.0 / (size * pMin), betaCurrent);

        // Stratified sampling: divide [0, totalPriority) into batchSize segments
        double segmentSize = totalPriority / batchSize;

        for (int i = 0; i < batchSize; i++) {
            double low = segmentSize * i;
            double high = segmentSize * (i + 1);
            double sample = RandomUtil.nextDouble(low, high);

            int bufferIndex = sampleFromTree(sample);
            // Ensure valid index
            bufferIndex = Math.max(0, Math.min(bufferIndex, size - 1));

            entries[i] = buffer[bufferIndex];
            indices[i] = bufferIndex;

            // Compute IS weight: w_i = (1 / (N * P(i)))^beta / maxWeight
            double leafPriority = getLeafPriority(bufferIndex);
            double probability = leafPriority / totalPriority;
            if (probability <= 0.0) probability = 1e-10;
            double weight = Math.pow(1.0 / (size * probability), betaCurrent);
            importanceWeights[i] = weight / maxWeight; // Normalize so max = 1.0
        }

        return new SampledBatch(entries, indices, importanceWeights);
    }

    /**
     * Fallback: uniform random sampling when priorities are all zero.
     */
    @Nonnull
    private SampledBatch sampleUniform(int batchSize) {
        ReplayEntry[] entries = new ReplayEntry[batchSize];
        int[] indices = new int[batchSize];
        double[] weights = new double[batchSize];
        for (int i = 0; i < batchSize; i++) {
            int idx = RandomUtil.nextInt(size);
            entries[i] = buffer[idx];
            indices[i] = idx;
            weights[i] = 1.0;
        }
        return new SampledBatch(entries, indices, weights);
    }

    // ═════════════════════════════════════════════════════════════
    //  PRIORITY UPDATE
    // ═════════════════════════════════════════════════════════════

    /**
     * Updates the priorities for a batch of entries after training.
     * Called after computing new TD-errors for sampled experiences.
     *
     * @param bufferIndices the buffer indices of the entries to update
     * @param newTDErrors   the new absolute TD-errors
     */
    public void updatePriorities(@Nonnull int[] bufferIndices, @Nonnull double[] newTDErrors) {
        for (int i = 0; i < bufferIndices.length; i++) {
            int idx = bufferIndices[i];
            if (idx < 0 || idx >= size) continue;

            double priority = Math.abs(newTDErrors[i]) + epsilon;
            if (priority > maxPriorityEverSeen) {
                maxPriorityEverSeen = priority;
            }

            if (buffer[idx] != null) {
                buffer[idx].tdErrorPriority = priority;
            }

            updateTree(idx, Math.pow(priority, alpha));
        }
    }

    // ═════════════════════════════════════════════════════════════
    //  LIFECYCLE
    // ═════════════════════════════════════════════════════════════

    /**
     * Called at the end of each game. Increments the game counter and anneals beta.
     */
    public void onGameEnd() {
        currentGameNumber++;
        totalGamesProcessed++;

        // Anneal beta linearly from betaStart toward betaEnd
        if (totalGamesProcessed < betaAnnealGames) {
            betaCurrent = betaStart + (betaEnd - betaStart)
                    * ((double) totalGamesProcessed / betaAnnealGames);
        } else {
            betaCurrent = betaEnd;
        }
    }

    /**
     * Returns the current number of valid entries in the buffer.
     *
     * @return the size
     */
    public int getSize() {
        return size;
    }

    /**
     * Returns the buffer capacity.
     *
     * @return the capacity
     */
    public int getCapacity() {
        return capacity;
    }

    /**
     * Returns the current beta value for importance sampling correction.
     *
     * @return the current beta
     */
    public double getBetaCurrent() {
        return betaCurrent;
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
     * @param gameNumber the game number
     */
    public void setCurrentGameNumber(long gameNumber) {
        this.currentGameNumber = gameNumber;
        this.totalGamesProcessed = gameNumber;
        // Recalculate beta based on loaded game count
        if (totalGamesProcessed < betaAnnealGames) {
            betaCurrent = betaStart + (betaEnd - betaStart)
                    * ((double) totalGamesProcessed / betaAnnealGames);
        } else {
            betaCurrent = betaEnd;
        }
    }

    /**
     * Returns the max priority ever seen. Used as initial priority for new entries.
     *
     * @return the max priority
     */
    public double getMaxPriorityEverSeen() {
        return maxPriorityEverSeen;
    }

    /**
     * Sets the max priority. Used when loading from disk.
     *
     * @param maxPriority the max priority
     */
    public void setMaxPriorityEverSeen(double maxPriority) {
        this.maxPriorityEverSeen = maxPriority;
    }

    /**
     * Returns the raw buffer array. Used for serialization snapshots.
     * The caller MUST deep-copy before async writing.
     *
     * @return the buffer array (may contain nulls for unfilled slots)
     */
    @Nonnull
    ReplayEntry[] getBuffer() {
        return buffer;
    }

    /**
     * Returns the current head position. Used for serialization.
     *
     * @return the head index
     */
    int getHead() {
        return head;
    }

    /**
     * Restores buffer state from deserialized data.
     *
     * @param entries the deserialized entries
     * @param loadedHead the head position
     * @param loadedSize the number of valid entries
     */
    public void restoreFromLoad(@Nonnull ReplayEntry[] entries, int loadedHead, int loadedSize) {
        int count = Math.min(entries.length, capacity);
        for (int i = 0; i < count; i++) {
            buffer[i] = entries[i];
            if (entries[i] != null) {
                updateTree(i, Math.pow(entries[i].tdErrorPriority, alpha));
            }
        }
        this.head = loadedHead % capacity;
        this.size = Math.min(loadedSize, capacity);
    }

    // ═════════════════════════════════════════════════════════════
    //  SUM-TREE IMPLEMENTATION
    // ═════════════════════════════════════════════════════════════

    /**
     * Updates the sum-tree and min-tree for a given buffer index.
     *
     * @param bufferIndex the buffer index (maps to leaf at tree index capacity + bufferIndex)
     * @param priority    the priority^alpha value
     */
    private void updateTree(int bufferIndex, double priority) {
        int leafIndex = capacity + bufferIndex;

        sumTree[leafIndex] = priority;
        minTree[leafIndex] = priority;

        // Propagate up the tree
        int parent = leafIndex / 2;
        while (parent >= 1) {
            int left = parent * 2;
            int right = parent * 2 + 1;
            sumTree[parent] = sumTree[left] + sumTree[right];
            minTree[parent] = Math.min(minTree[left], minTree[right]);
            parent /= 2;
        }
    }

    /**
     * Samples a buffer index from the sum-tree using a random value.
     *
     * <p>Walk from root to leaf: at each node, go left if the sample falls
     * within the left child's sum, otherwise subtract the left sum and go right.</p>
     *
     * @param sample a random value in [0, totalPriority)
     * @return the buffer index of the selected leaf
     */
    private int sampleFromTree(double sample) {
        int node = 1; // Start at root

        while (node < capacity) {
            int left = node * 2;
            int right = node * 2 + 1;

            if (left >= sumTree.length) break;

            if (sample <= sumTree[left]) {
                node = left;
            } else {
                sample -= sumTree[left];
                node = right;
            }
        }

        // Convert tree leaf index to buffer index
        int bufferIndex = node - capacity;
        return Math.max(0, Math.min(bufferIndex, size - 1));
    }

    /**
     * Returns the priority for a specific buffer index from the sum-tree.
     *
     * @param bufferIndex the buffer index
     * @return the priority value
     */
    private double getLeafPriority(int bufferIndex) {
        int leafIndex = capacity + bufferIndex;
        if (leafIndex >= sumTree.length) return 0.0;
        return sumTree[leafIndex];
    }

    // ═════════════════════════════════════════════════════════════
    //  INNER: SampledBatch
    // ═════════════════════════════════════════════════════════════

    /**
     * A sampled mini-batch from the replay buffer, including the entries,
     * their buffer indices (for priority updates), and their importance-sampling
     * correction weights.
     */
    public static class SampledBatch {

        /** The sampled replay entries. */
        public final ReplayEntry[] entries;

        /** The buffer indices of the sampled entries (for priority updates). */
        public final int[] bufferIndices;

        /** The importance-sampling weights, normalized so max = 1.0. */
        public final double[] importanceWeights;

        public SampledBatch(@Nonnull ReplayEntry[] entries,
                            @Nonnull int[] bufferIndices,
                            @Nonnull double[] importanceWeights) {
            this.entries = entries;
            this.bufferIndices = bufferIndices;
            this.importanceWeights = importanceWeights;
        }
    }
}
