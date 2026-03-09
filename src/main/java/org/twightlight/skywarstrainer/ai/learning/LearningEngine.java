// ═══════════════════════════════════════════════════════════════════
// FILE: src/main/java/org/twightlight/skywarstrainer/ai/learning/LearningModule.java
// ═══════════════════════════════════════════════════════════════════

package org.twightlight.skywarstrainer.ai.learning;

import org.twightlight.skywarstrainer.ai.decision.DecisionContext;
import org.twightlight.skywarstrainer.ai.decision.DecisionEngine;
import org.twightlight.skywarstrainer.ai.decision.DecisionEngine.BotAction;
import org.twightlight.skywarstrainer.bot.TrainerBot;
import org.twightlight.skywarstrainer.util.DebugLogger;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.Arrays;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * Top-level orchestrator for the reinforcement learning system.
 *
 * <p>Created once per bot in {@code TrainerBot.initializeAllSubsystems()}. Coordinates
 * all learning sub-components: state encoding, experience recording, eligibility traces,
 * reward calculation, replay training, memory pruning, and weight adjustment.</p>
 *
 * <p>The LearningModule is the single integration point between the learning system
 * and the existing AI architecture. It provides:</p>
 * <ul>
 *   <li>{@link #tick()} — called from TrainerBot's tick loop every 10 ticks</li>
 *   <li>{@link #getWeightAdjustments()} — called from DecisionEngine.evaluate() each cycle</li>
 *   <li>{@link #onDecisionMade(BotAction, Map)} — called after action selection</li>
 *   <li>{@link #onSignificantEvent(String, double)} — called on game events</li>
 *   <li>{@link #onGameStart()} / {@link #onGameEnd(boolean, int, int, double)} — lifecycle</li>
 * </ul>
 *
 * <p>The MemoryBank and ReplayBuffer are shared singletons across all bots. Knowledge
 * learned by one bot immediately benefits all others.</p>
 */
public class LearningEngine {

    private final TrainerBot bot;
    private final MemoryBank memoryBank;
    private final ReplayBuffer replayBuffer;
    private final StateEncoder stateEncoder;
    private final RewardCalculator rewardCalculator;
    private final ExperienceRecorder experienceRecorder;
    private final WeightAdjuster weightAdjuster;
    private final EligibilityTraceTable eligibilityTraces;
    private final MemoryPruner memoryPruner;
    private final LearningConfig config;

    // ── Cached current-state data ──
    private int lastStateId;
    private double[] lastStateVector;
    private BotAction lastAction;
    private Map<BotAction, Double> cachedAdjustments;
    private boolean adjustmentsDirty;

    // ── Safety: emergency brake tracking ──
    private double[] recentGameRewards;
    private int recentGameRewardHead;
    private int recentGameRewardCount;
    private boolean learningPaused;

    // ── Performance tracking ──
    private long totalGamesLearned;

    /**
     * Creates a new LearningModule for the given bot.
     *
     * @param bot                the owning trainer bot
     * @param sharedMemoryBank   the server-wide shared memory bank
     * @param sharedReplayBuffer the server-wide shared replay buffer
     */
    public LearningEngine(@Nonnull TrainerBot bot,
                          @Nonnull MemoryBank sharedMemoryBank,
                          @Nonnull ReplayBuffer sharedReplayBuffer) {
        this.bot = bot;
        this.memoryBank = sharedMemoryBank;
        this.replayBuffer = sharedReplayBuffer;
        this.config = bot.getPlugin().getLearningManager().getLearningConfig();
        this.stateEncoder = new StateEncoder(config.getBinsPerDimension());
        this.rewardCalculator = new RewardCalculator(config);
        this.experienceRecorder = new ExperienceRecorder(stateEncoder, rewardCalculator, memoryBank, config);
        this.weightAdjuster = new WeightAdjuster(config, stateEncoder);
        this.eligibilityTraces = new EligibilityTraceTable(config);
        this.memoryPruner = new MemoryPruner(config);

        this.lastStateId = -1;
        this.lastStateVector = null;
        this.lastAction = null;
        this.cachedAdjustments = new EnumMap<BotAction, Double>(BotAction.class);
        this.adjustmentsDirty = true;

        // Emergency brake: rolling window of 40 games (two windows of 20)
        this.recentGameRewards = new double[40];
        this.recentGameRewardHead = 0;
        this.recentGameRewardCount = 0;
        this.learningPaused = false;
        this.totalGamesLearned = 0;
    }

    // ═════════════════════════════════════════════════════════════
    //  TICK
    // ═════════════════════════════════════════════════════════════

    /**
     * Called from TrainerBot.tick() every 10 ticks. Encodes the current state
     * and checks if the state has changed since last tick (marks adjustments dirty).
     */
    public void tick() {
        DecisionEngine de = bot.getDecisionEngine();
        if (de == null) return;

        DecisionContext context = de.getContext();
        if (context == null) return;

        // Encode current state
        double[] currentVector = stateEncoder.encode(context);
        int currentStateId = stateEncoder.discretize(currentVector);

        // Check if state changed
        if (currentStateId != lastStateId) {
            adjustmentsDirty = true;
            lastStateId = currentStateId;
            // Copy the vector since encoder pools it
            if (lastStateVector == null) {
                lastStateVector = new double[StateEncoder.STATE_VECTOR_SIZE];
            }
            System.arraycopy(currentVector, 0, lastStateVector, 0, StateEncoder.STATE_VECTOR_SIZE);
        }
    }

    // ═════════════════════════════════════════════════════════════
    //  GAME LIFECYCLE
    // ═════════════════════════════════════════════════════════════

    /**
     * Called when the bot's game begins. Resets per-game components.
     */
    public void onGameStart() {
        memoryBank.onGameStart();
        eligibilityTraces.reset();
        experienceRecorder.reset();
        lastStateId = -1;
        lastStateVector = null;
        lastAction = null;
        adjustmentsDirty = true;
    }

    /**
     * Called when the bot's game ends. This is the heaviest method — runs
     * terminal reward computation, experience flushing, replay training,
     * memory pruning, and safety checks.
     *
     * <p>Must complete in &lt;5ms total.</p>
     *
     * @param won                   whether the bot won
     * @param kills                 number of kills this game
     * @param deaths                number of deaths this game (0 or 1)
     * @param survivalTimeFraction  fraction of game time the bot survived [0.0, 1.0]
     */
    public void onGameEnd(boolean won, int kills, int deaths, double survivalTimeFraction) {
        if (learningPaused) return;

        // 1. Compute terminal reward
        double terminalReward = 0.0;
        if (won) {
            terminalReward += config.getRewardWin();
        } else if (deaths > 0) {
            terminalReward += config.getRewardDeath();
        } else {
            terminalReward += config.getRewardLose();
        }
        terminalReward += kills * config.getRewardKill();
        if (survivalTimeFraction > 0.75) {
            terminalReward += config.getRewardSurvivedTop3();
        }

        // 2. Flush pending experiences from recorder (terminal)
        List<ExperienceEntry> gameExperiences = experienceRecorder.onGameEnd(terminalReward);

        // 3. Add all experiences to replay buffer
        double maxPriority = replayBuffer.getMaxPriorityEverSeen();
        for (int i = 0; i < gameExperiences.size(); i++) {
            ExperienceEntry exp = gameExperiences.get(i);
            ReplayEntry replay = ReplayEntry.fromExperience(exp, maxPriority);
            replayBuffer.add(replay);
        }

        // 4. Run replay training (if enabled and buffer has data)
        if (config.isReplayEnabled() && replayBuffer.getSize() > 0) {
            runReplayTraining(gameExperiences);
        }

        // 5. Increment visits in MemoryBank for new experiences
        for (int i = 0; i < gameExperiences.size(); i++) {
            ExperienceEntry exp = gameExperiences.get(i);
            memoryBank.incrementVisit(exp.discretizedStateId, exp.actionOrdinal);
        }

        // 6. Run memory pruner
        memoryPruner.prune(memoryBank);

        // 7. Clear eligibility traces
        eligibilityTraces.reset();

        // 8. Update replay buffer game counter
        replayBuffer.onGameEnd();

        // 9. Mark adjustments dirty
        adjustmentsDirty = true;

        // 10. Track for emergency brake
        trackGamePerformance(terminalReward);

        totalGamesLearned++;

        DebugLogger.log(bot, "Learning: game ended. QTable=%d, Replay=%d, experiences=%d, reward=%.2f",
                memoryBank.size(), replayBuffer.getSize(), gameExperiences.size(), terminalReward);
    }

    /**
     * Called from DecisionEngine.evaluate() right after action selection.
     * Records the current (stateId, action) and updates eligibility traces.
     *
     * @param action the action selected by the DecisionEngine
     * @param scores the score map from the evaluation (for debug)
     */
    public void onDecisionMade(@Nonnull BotAction action, @Nonnull Map<BotAction, Double> scores) {
        if (learningPaused) return;

        DecisionEngine de = bot.getDecisionEngine();
        if (de == null) return;

        // Encode current state
        DecisionContext context = de.getContext();
        double[] currentVector = stateEncoder.encode(context);
        int currentStateId = stateEncoder.discretize(currentVector);

        // Update eligibility traces: decay all, then set current to 1.0
        eligibilityTraces.update(currentStateId, action.ordinal());

        // If there was a previous decision, compute the within-game TD update
        if (lastAction != null && lastStateVector != null) {
            // Compute TD-error for within-game eligibility trace update
            double effectiveLR = computeEffectiveLearningRate();
            double gamma = config.getDiscountFactor();

            double oldQ = memoryBank.getQValue(lastStateId, lastAction.ordinal());
            double maxNextQ = getMaxQValue(currentStateId);
            // We don't have the step reward here (it's computed by ExperienceRecorder),
            // so we use a simplified TD update based on Q-value change only.
            // The full reward-based update happens in replay training.
            double tdError = gamma * maxNextQ - oldQ;

            // Apply TD-error through eligibility traces (propagates to all recent states)
            eligibilityTraces.applyTDError(tdError, effectiveLR, memoryBank);
        }

        // Notify experience recorder
        double[] vectorCopy = Arrays.copyOf(currentVector, currentVector.length);
        experienceRecorder.onDecisionMade(currentStateId, vectorCopy, action);

        // Update centroid for this state
        memoryBank.updateCentroid(currentStateId, currentVector);

        // Store as "last" decision
        lastStateId = currentStateId;
        if (lastStateVector == null) {
            lastStateVector = new double[StateEncoder.STATE_VECTOR_SIZE];
        }
        System.arraycopy(currentVector, 0, lastStateVector, 0, StateEncoder.STATE_VECTOR_SIZE);
        lastAction = action;
        adjustmentsDirty = true;
    }

    /**
     * Called from GameEventListener or CombatEngine on significant events.
     * Forwards to the ExperienceRecorder for reward accumulation.
     *
     * @param eventType the event type (e.g., "kill", "death", "health_lost")
     * @param value     the event's numeric value
     */
    public void onSignificantEvent(@Nonnull String eventType, double value) {
        if (learningPaused) return;
        experienceRecorder.onGameEvent(eventType, value);
    }

    // ═════════════════════════════════════════════════════════════
    //  WEIGHT ADJUSTMENTS (called by DecisionEngine)
    // ═════════════════════════════════════════════════════════════

    /**
     * Returns the learned weight adjustments for the current state.
     *
     * <p>Returns a map of BotAction → multiplier. Default is 1.0 (no change).
     * Called frequently (every evaluate cycle), so results are cached and only
     * recomputed when adjustmentsDirty is true.</p>
     *
     * <p>Safety: if more than 70% of actions have multiplier &lt; 0.5 (learned
     * helplessness), resets all to 1.0 for this tick and logs a warning.</p>
     *
     * @return the weight adjustments map
     */
    @Nonnull
    public Map<BotAction, Double> getWeightAdjustments() {
        if (!adjustmentsDirty) {
            return cachedAdjustments;
        }

        if (lastStateId < 0 || learningPaused) {
            cachedAdjustments.clear();
            adjustmentsDirty = false;
            return cachedAdjustments;
        }

        cachedAdjustments = weightAdjuster.computeAdjustments(lastStateId, memoryBank);
        adjustmentsDirty = false;

        // Safety check: learned helplessness detection
        if (!cachedAdjustments.isEmpty()) {
            int suppressedCount = 0;
            for (Double mult : cachedAdjustments.values()) {
                if (mult < 0.5) suppressedCount++;
            }
            double suppressedFraction = (double) suppressedCount / cachedAdjustments.size();
            if (suppressedFraction > 0.7) {
                DebugLogger.log(bot, "WARNING: Learned helplessness detected (%.0f%% suppressed). Resetting adjustments.", suppressedFraction * 100);
                cachedAdjustments.clear(); // Empty map = all 1.0
            }
        }

        return cachedAdjustments;
    }

    // ═════════════════════════════════════════════════════════════
    //  REPLAY TRAINING
    // ═════════════════════════════════════════════════════════════

    /**
     * Runs mini-batch replay training from the replay buffer.
     * Called at game end.
     *
     * @param newExperiences the experiences from the game just ended (for context, not used directly)
     */
    private void runReplayTraining(@Nonnull List<ExperienceEntry> newExperiences) {
        int rounds = config.getReplayRoundsPerGame();
        int batchSize = config.getMiniBatchSize();
        double gamma = config.getDiscountFactor();
        double effectiveLR = computeEffectiveLearningRate();
        double epsilon = config.getPriorityEpsilon();

        for (int round = 0; round < rounds; round++) {
            ReplayBuffer.SampledBatch batch = replayBuffer.sample(batchSize);
            if (batch == null) break;

            double[] newTDErrors = new double[batch.entries.length];

            for (int i = 0; i < batch.entries.length; i++) {
                ReplayEntry entry = batch.entries[i];
                if (entry == null) continue;

                double isWeight = batch.importanceWeights[i];

                // Compute discretized state IDs
                int sId = stateEncoder.discretize(entry.state);
                int nextSId = stateEncoder.discretize(entry.nextState);

                double currentQ = memoryBank.getQValue(sId, entry.actionOrdinal);

                double target;
                if (entry.terminal) {
                    // Terminal state: no bootstrap
                    target = entry.reward;
                } else {
                    // Non-terminal: standard Q-learning target
                    double maxNextQ = getMaxQValue(nextSId);
                    target = entry.reward + gamma * maxNextQ;
                }

                double tdError = target - currentQ;
                double newQ = currentQ + effectiveLR * isWeight * tdError;

                memoryBank.updateQValue(sId, entry.actionOrdinal, newQ);
                newTDErrors[i] = tdError;
            }

            // Update priorities in the replay buffer
            replayBuffer.updatePriorities(batch.bufferIndices, newTDErrors);
        }
    }

    // ═════════════════════════════════════════════════════════════
    //  SAFETY: EMERGENCY BRAKE
    // ═════════════════════════════════════════════════════════════

    /**
     * Tracks game performance for the emergency brake. If the rolling 20-game
     * average drops by more than 50% compared to the average from 40 games ago,
     * pauses learning automatically.
     *
     * @param reward the total reward from the game that just ended
     */
    private void trackGamePerformance(double reward) {
        recentGameRewards[recentGameRewardHead] = reward;
        recentGameRewardHead = (recentGameRewardHead + 1) % recentGameRewards.length;
        if (recentGameRewardCount < recentGameRewards.length) {
            recentGameRewardCount++;
        }

        // Need at least 40 games of history
        if (recentGameRewardCount < recentGameRewards.length) return;

        // Compute recent 20-game average (most recent 20)
        double recentSum = 0.0;
        double olderSum = 0.0;
        int windowSize = recentGameRewards.length / 2; // 20

        for (int i = 0; i < windowSize; i++) {
            int recentIdx = (recentGameRewardHead - 1 - i + recentGameRewards.length) % recentGameRewards.length;
            int olderIdx = (recentGameRewardHead - 1 - windowSize - i + recentGameRewards.length) % recentGameRewards.length;
            recentSum += recentGameRewards[recentIdx];
            olderSum += recentGameRewards[olderIdx];
        }

        double recentAvg = recentSum / windowSize;
        double olderAvg = olderSum / windowSize;

        // Check for catastrophic performance drop
        if (olderAvg > 0 && recentAvg < olderAvg * 0.5) {
            learningPaused = true;
            DebugLogger.log(bot, "EMERGENCY BRAKE: Performance dropped %.0f%%. Learning paused. " +
                            "Recent avg=%.2f, Older avg=%.2f. Use /swt learning resume to re-enable.",
                    (1.0 - recentAvg / olderAvg) * 100, recentAvg, olderAvg);
        }
    }

    // ═════════════════════════════════════════════════════════════
    //  HELPERS
    // ═════════════════════════════════════════════════════════════

    /**
     * Returns the maximum Q-value across all actions for a given state.
     * Returns 0.0 if the state is unknown.
     *
     * @param stateId the discretized state ID
     * @return the max Q-value
     */
    private double getMaxQValue(int stateId) {
        double[] qValues = memoryBank.getQValues(stateId);
        if (qValues == null) return 0.0;

        double max = Double.NEGATIVE_INFINITY;
        for (double q : qValues) {
            if (q > max) max = q;
        }
        return max == Double.NEGATIVE_INFINITY ? 0.0 : max;
    }

    /**
     * Computes the effective learning rate, accounting for difficulty scaling
     * and learning rate decay after max-games-before-stable.
     *
     * @return the effective learning rate
     */
    private double computeEffectiveLearningRate() {
        double baseLR = config.getLearningRate();
        double difficultyLR = bot.getDifficultyProfile().getLearningRate();
        int gamesPlayed = bot.getProfile().getGamesPlayed();
        int maxGames = config.getMaxGamesBeforeStable();

        double effectiveLR = baseLR * (difficultyLR / 0.1); // Normalize around MEDIUM's 0.1

        // Apply learning rate decay after stabilization threshold
        if (gamesPlayed > maxGames) {
            effectiveLR *= config.getStabilityLrMultiplier();
        }

        return effectiveLR;
    }

    // ═════════════════════════════════════════════════════════════
    //  QUERIES (for commands / debug)
    // ═════════════════════════════════════════════════════════════

    /**
     * Returns whether learning is currently paused (by emergency brake or manual pause).
     *
     * @return true if paused
     */
    public boolean isLearningPaused() {
        return learningPaused;
    }

    /**
     * Sets the learning pause state. Used by /swt learning pause/resume commands.
     *
     * @param paused true to pause, false to resume
     */
    public void setLearningPaused(boolean paused) {
        this.learningPaused = paused;
        if (!paused) {
            DebugLogger.log(bot, "Learning resumed.");
        }
    }

    /**
     * Returns the total number of games this module has learned from.
     *
     * @return the game count
     */
    public long getTotalGamesLearned() {
        return totalGamesLearned;
    }

    /**
     * Returns the current discretized state ID (for debug).
     *
     * @return the state ID, or -1 if not yet computed
     */
    public int getCurrentStateId() {
        return lastStateId;
    }

    /**
     * Returns the current state vector (for debug). May be null.
     *
     * @return the last encoded state vector, or null
     */
    @Nullable
    public double[] getCurrentStateVector() {
        return lastStateVector;
    }

    /**
     * Returns the eligibility trace count (for debug).
     *
     * @return the number of active traces
     */
    public int getTraceCount() {
        return eligibilityTraces.size();
    }

    /**
     * Returns the pending experience count (for debug).
     *
     * @return the count
     */
    public int getPendingExperienceCount() {
        return experienceRecorder.getPendingCount();
    }

    /**
     * Returns the effective learning rate (for debug).
     *
     * @return the current effective LR
     */
    public double getEffectiveLearningRate() {
        return computeEffectiveLearningRate();
    }
}