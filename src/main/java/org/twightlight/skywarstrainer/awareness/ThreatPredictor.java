package org.twightlight.skywarstrainer.awareness;

import org.bukkit.Location;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.util.Vector;
import org.twightlight.skywarstrainer.bot.TrainerBot;
import org.twightlight.skywarstrainer.config.DifficultyConfig.DifficultyProfile;
import org.twightlight.skywarstrainer.util.DebugLogger;
import org.twightlight.skywarstrainer.util.MathUtil;
import org.twightlight.skywarstrainer.util.RandomUtil;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.UUID;

/**
 * Predicts what enemy players will do next based on their recent movement
 * patterns, heading changes, and positional tendencies.
 *
 * <p>Unlike the {@link ThreatMap} which tracks WHERE enemies are, the
 * ThreatPredictor analyzes HOW they are moving to predict their INTENT.
 * This feeds into both the DecisionEngine (via ThreatPredictionConsideration)
 * and the StrategyPlanner (informing plan generation).</p>
 *
 * <p>Prediction accuracy is controlled by the difficulty profile's
 * {@code threatPredictionAccuracy} parameter. Lower accuracy introduces
 * noise and incorrect predictions.</p>
 */
public class ThreatPredictor {

    /** Maximum number of position samples stored per enemy for analysis. */
    private static final int MAX_POSITION_SAMPLES = 10;

    /** Distance threshold for considering an enemy to be "approaching" the bot. */
    private static final double APPROACH_THRESHOLD = 0.15;

    /** Speed threshold below which an enemy is considered "stationary" or "camping". */
    private static final double CAMPING_SPEED_THRESHOLD = 0.05;

    private final TrainerBot bot;

    /** Stored movement histories per tracked enemy. */
    private final Map<UUID, MovementHistory> histories;

    /** Current predictions per enemy. */
    private final Map<UUID, PredictedBehavior> predictions;

    /**
     * Creates a new ThreatPredictor for the given bot.
     *
     * @param bot the owning trainer bot
     */
    public ThreatPredictor(@Nonnull TrainerBot bot) {
        this.bot = bot;
        this.histories = new HashMap<>();
        this.predictions = new HashMap<>();
    }

    // ─── Tick ───────────────────────────────────────────────────

    /**
     * Updates the predictor by sampling current enemy positions and
     * computing predictions. Called every N ticks (configurable).
     */
    public void tick() {
        DifficultyProfile diff = bot.getDifficultyProfile();
        if (diff == null || !diff.isThreatPredictionEnabled()) {
            return;
        }

        double accuracy = diff.getThreatPredictionAccuracy();
        LivingEntity botEntity = bot.getLivingEntity();
        if (botEntity == null) return;

        Location botLoc = botEntity.getLocation();
        double awarenessRadius = diff.getAwarenessRadius();

        // Sample current positions of all nearby players
        for (Entity entity : botEntity.getNearbyEntities(awarenessRadius, awarenessRadius, awarenessRadius)) {
            if (!(entity instanceof Player)) continue;
            if (entity.getUniqueId().equals(bot.getBotId())) continue;

            Player player = (Player) entity;
            UUID playerId = player.getUniqueId();
            Location playerLoc = player.getLocation();

            // Get or create movement history
            MovementHistory history = histories.get(playerId);
            if (history == null) {
                history = new MovementHistory();
                histories.put(playerId, history);
            }

            // Add current sample
            history.addSample(playerLoc, bot.getLocalTickCount());

            // Predict behavior if we have enough samples
            if (history.sampleCount >= 3) {
                PredictedBehavior prediction = analyzeBehavior(
                        playerId, history, botLoc, accuracy);
                predictions.put(playerId, prediction);
            }
        }

        // Clean up stale entries for players who left awareness range
        long currentTick = bot.getLocalTickCount();
        Iterator<Map.Entry<UUID, MovementHistory>> it = histories.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<UUID, MovementHistory> entry = it.next();
            if (currentTick - entry.getValue().lastSampleTick > 100) {
                it.remove();
                predictions.remove(entry.getKey());
            }
        }
    }

    /**
     * Analyzes a player's movement history and predicts their intent.
     *
     * @param playerId the player's UUID
     * @param history  the player's movement history
     * @param botLoc   the bot's current location
     * @param accuracy the prediction accuracy [0,1]
     * @return the predicted behavior
     */
    @Nonnull
    private PredictedBehavior analyzeBehavior(@Nonnull UUID playerId,
                                              @Nonnull MovementHistory history,
                                              @Nonnull Location botLoc,
                                              double accuracy) {
        // Calculate movement vector (average direction over recent samples)
        Vector avgDirection = history.getAverageDirection();
        double speed = history.getAverageSpeed();
        Location currentPos = history.getLatestPosition();

        if (currentPos == null) {
            return new PredictedBehavior(playerId, PredictedIntent.UNKNOWN,
                    0.0, null, bot.getLocalTickCount());
        }

        // Calculate if enemy is moving toward the bot
        Vector toBot = botLoc.toVector().subtract(currentPos.toVector());
        double distanceToBot = toBot.length();
        if (distanceToBot > 0.01) {
            toBot.normalize();
        }

        double dotProduct = (avgDirection.length() > 0.01)
                ? avgDirection.normalize().dot(toBot) : 0.0;

        // Predict position 2 seconds ahead (40 ticks)
        Location predictedPos = currentPos.clone().add(
                avgDirection.getX() * 40,
                avgDirection.getY() * 40,
                avgDirection.getZ() * 40);

        // Determine intent based on movement analysis
        PredictedIntent intent;
        double confidence;

        if (speed < CAMPING_SPEED_THRESHOLD) {
            // Very slow or stationary — camping or looting
            if (isNearChest(currentPos)) {
                intent = PredictedIntent.LOOTING;
                confidence = 0.7;
            } else {
                intent = PredictedIntent.CAMPING;
                confidence = 0.6;
            }
        } else if (dotProduct > APPROACH_THRESHOLD && distanceToBot < 40) {
            // Moving toward the bot
            intent = PredictedIntent.APPROACHING_BOT;
            confidence = MathUtil.clamp(dotProduct, 0.3, 0.95);
        } else if (speed > 0.2 && isOnBridge(currentPos)) {
            // Fast movement on a narrow structure — bridging
            intent = PredictedIntent.BRIDGING;
            confidence = 0.65;
        } else if (dotProduct < -APPROACH_THRESHOLD && distanceToBot < 25) {
            // Moving away from the bot
            intent = PredictedIntent.FLEEING;
            confidence = MathUtil.clamp(-dotProduct, 0.3, 0.85);
        } else if (speed > 0.1) {
            // Moving but not toward bot — approaching someone else or roaming
            intent = PredictedIntent.APPROACHING_OTHER;
            confidence = 0.4;
        } else {
            intent = PredictedIntent.UNKNOWN;
            confidence = 0.2;
        }

        // Apply accuracy scaling: lower accuracy → more noise, potential wrong predictions
        if (accuracy < 1.0) {
            confidence *= accuracy;

            // Chance to give a wrong prediction based on inaccuracy
            double wrongPredictionChance = (1.0 - accuracy) * 0.3;
            if (RandomUtil.chance(wrongPredictionChance)) {
                PredictedIntent[] allIntents = PredictedIntent.values();
                intent = allIntents[RandomUtil.nextInt(0, allIntents.length - 1)];
                confidence *= 0.5;
            }
        }

        return new PredictedBehavior(playerId, intent, confidence, predictedPos, bot.getLocalTickCount());
    }

    /**
     * Checks if a location is near an unlooted chest.
     */
    private boolean isNearChest(@Nonnull Location loc) {
        ChestLocator locator = bot.getChestLocator();
        if (locator == null) return false;
        ChestLocator.ChestInfo nearest = locator.getNearestUnlootedChest();
        if (nearest == null || nearest.location == null) return false;
        return loc.distanceSquared(nearest.location) < 16.0; // within 4 blocks
    }

    /**
     * Heuristic check if a location is on a bridge (narrow structure over void/air).
     */
    private boolean isOnBridge(@Nonnull Location loc) {
        if (loc.getWorld() == null) return false;
        // Check if blocks below are air in adjacent directions
        Location below = loc.clone().subtract(0, 1, 0);
        int airCount = 0;
        if (below.clone().add(1, 0, 0).getBlock().getType().name().equals("AIR")) airCount++;
        if (below.clone().add(-1, 0, 0).getBlock().getType().name().equals("AIR")) airCount++;
        if (below.clone().add(0, 0, 1).getBlock().getType().name().equals("AIR")) airCount++;
        if (below.clone().add(0, 0, -1).getBlock().getType().name().equals("AIR")) airCount++;
        return airCount >= 3; // At least 3 of 4 sides are air → narrow structure
    }

    // ─── Lifecycle ──────────────────────────────────────────────

    /**
     * Resets all tracking data. Called on game start/end.
     */
    public void reset() {
        histories.clear();
        predictions.clear();
    }

    // ─── Queries ────────────────────────────────────────────────

    /**
     * Returns all current predictions as an unmodifiable map.
     *
     * @return map of player UUID to predicted behavior
     */
    @Nonnull
    public Map<UUID, PredictedBehavior> getPredictions() {
        return Collections.unmodifiableMap(predictions);
    }

    /**
     * Returns the prediction for a specific player.
     *
     * @param playerId the player's UUID
     * @return the predicted behavior, or null if not tracked
     */
    @Nullable
    public PredictedBehavior getPrediction(@Nonnull UUID playerId) {
        return predictions.get(playerId);
    }

    /**
     * Returns the number of enemies currently being tracked.
     *
     * @return the tracked enemy count
     */
    public int getTrackedCount() {
        return predictions.size();
    }

    // ═════════════════════════════════════════════════════════════
    //  INNER: PredictedBehavior
    // ═════════════════════════════════════════════════════════════

    /**
     * Represents a predicted behavior for a single tracked enemy.
     */
    public static final class PredictedBehavior {

        /** The player this prediction is for. */
        public final UUID playerId;

        /** The predicted intent/behavior. */
        public final PredictedIntent intent;

        /** Confidence in this prediction [0.0, 1.0]. */
        public final double confidence;

        /** Predicted position of the enemy in ~2 seconds. May be null. */
        @Nullable
        public final Location predictedPosition;

        /** The tick at which this prediction was made. */
        public final long predictionTick;

        public PredictedBehavior(@Nonnull UUID playerId,
                                 @Nonnull PredictedIntent intent,
                                 double confidence,
                                 @Nullable Location predictedPosition,
                                 long predictionTick) {
            this.playerId = playerId;
            this.intent = intent;
            this.confidence = confidence;
            this.predictedPosition = predictedPosition;
            this.predictionTick = predictionTick;
        }

        @Override
        public String toString() {
            return String.format("Predicted{player=%s, intent=%s, conf=%.2f}",
                    playerId.toString().substring(0, 8), intent.name(), confidence);
        }
    }

    // ═════════════════════════════════════════════════════════════
    //  INNER: PredictedIntent
    // ═════════════════════════════════════════════════════════════

    /**
     * Enum of predicted enemy behaviors.
     */
    public enum PredictedIntent {
        /** Enemy is moving toward this bot. */
        APPROACHING_BOT,
        /** Enemy is moving toward another player. */
        APPROACHING_OTHER,
        /** Enemy is building a bridge. */
        BRIDGING,
        /** Enemy is looting chests. */
        LOOTING,
        /** Enemy is moving away from threats. */
        FLEEING,
        /** Enemy is stationary / holding position. */
        CAMPING,
        /** Unable to determine intent. */
        UNKNOWN
    }

    // ═════════════════════════════════════════════════════════════
    //  INNER: MovementHistory
    // ═════════════════════════════════════════════════════════════

    /**
     * Stores recent position samples for a single tracked enemy.
     */
    private static final class MovementHistory {

        final Location[] positions;
        final long[] ticks;
        int head;
        int sampleCount;
        long lastSampleTick;

        MovementHistory() {
            this.positions = new Location[MAX_POSITION_SAMPLES];
            this.ticks = new long[MAX_POSITION_SAMPLES];
            this.head = 0;
            this.sampleCount = 0;
            this.lastSampleTick = 0;
        }

        void addSample(@Nonnull Location pos, long tick) {
            positions[head] = pos.clone();
            ticks[head] = tick;
            head = (head + 1) % MAX_POSITION_SAMPLES;
            if (sampleCount < MAX_POSITION_SAMPLES) {
                sampleCount++;
            }
            lastSampleTick = tick;
        }

        /**
         * Returns the average movement direction vector (per tick) over recent samples.
         */
        @Nonnull
        Vector getAverageDirection() {
            if (sampleCount < 2) return new Vector(0, 0, 0);

            double dx = 0, dy = 0, dz = 0;
            int pairs = 0;

            for (int i = 1; i < sampleCount; i++) {
                int curr = (head - 1 - (i - 1) + MAX_POSITION_SAMPLES) % MAX_POSITION_SAMPLES;
                int prev = (head - 1 - i + MAX_POSITION_SAMPLES) % MAX_POSITION_SAMPLES;

                if (positions[curr] == null || positions[prev] == null) continue;
                if (positions[curr].getWorld() == null || positions[prev].getWorld() == null) continue;
                if (!positions[curr].getWorld().equals(positions[prev].getWorld())) continue;

                long tickDiff = ticks[curr] - ticks[prev];
                if (tickDiff <= 0) continue;

                dx += (positions[curr].getX() - positions[prev].getX()) / tickDiff;
                dy += (positions[curr].getY() - positions[prev].getY()) / tickDiff;
                dz += (positions[curr].getZ() - positions[prev].getZ()) / tickDiff;
                pairs++;
            }

            if (pairs == 0) return new Vector(0, 0, 0);
            return new Vector(dx / pairs, dy / pairs, dz / pairs);
        }

        /**
         * Returns the average speed (blocks per tick) over recent samples.
         */
        double getAverageSpeed() {
            Vector dir = getAverageDirection();
            return dir.length();
        }

        /**
         * Returns the most recent position sample.
         */
        @Nullable
        Location getLatestPosition() {
            if (sampleCount == 0) return null;
            int latest = (head - 1 + MAX_POSITION_SAMPLES) % MAX_POSITION_SAMPLES;
            return positions[latest];
        }
    }
}
