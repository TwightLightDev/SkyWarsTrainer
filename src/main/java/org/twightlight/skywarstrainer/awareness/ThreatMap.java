package org.twightlight.skywarstrainer.awareness;

import org.bukkit.Location;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.util.Vector;
import org.twightlight.skywarstrainer.bot.TrainerBot;
import org.twightlight.skywarstrainer.config.DifficultyConfig.DifficultyProfile;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.*;

/**
 * Tracks all visible enemy positions, estimates their velocity vectors, predicts
 * future positions, and maintains a heat map of dangerous areas.
 *
 * <p>The ThreatMap is updated every tick with positions of visible enemies within
 * the bot's awareness radius. It provides data for the DecisionEngine and
 * CombatEngine to assess threats and choose targets.</p>
 *
 * <p>Each tracked enemy has:
 * <ul>
 *   <li>Current position</li>
 *   <li>Velocity estimate (direction + speed)</li>
 *   <li>Predicted future position (extrapolated from velocity)</li>
 *   <li>Last-seen timestamp</li>
 *   <li>Visibility status (currently visible or last-known position)</li>
 * </ul></p>
 */
public class ThreatMap {

    private final TrainerBot bot;

    /** Tracked enemies keyed by their entity UUID. */
    private final Map<UUID, ThreatEntry> threats;

    /**
     * Heat map: tracks how frequently areas are visited by enemies.
     * Key is an encoded grid cell position; value is the heat level (decays over time).
     */
    private final Map<Long, Double> heatMap;

    /** Grid cell size for the heat map (in blocks). */
    private static final int HEAT_GRID_SIZE = 4;

    /** Heat decay factor per tick. Heat reduces by this multiplier each tick. */
    private static final double HEAT_DECAY = 0.995;

    /** Heat added per tick when an enemy is in a grid cell. */
    private static final double HEAT_INCREMENT = 0.1;

    /**
     * Creates a new ThreatMap for the given bot.
     *
     * @param bot the owning trainer bot
     */
    public ThreatMap(@Nonnull TrainerBot bot) {
        this.bot = bot;
        this.threats = new HashMap<>();
        this.heatMap = new HashMap<>();
    }

    /**
     * Updates the threat map. Should be called every tick. Scans for nearby
     * enemies and updates their tracking data.
     */
    public void tick() {
        LivingEntity botEntity = bot.getLivingEntity();
        if (botEntity == null || botEntity.isDead()) return;

        DifficultyProfile diff = bot.getDifficultyProfile();
        double awarenessRadius = diff.getAwarenessRadius();
        double awarenessRadiusSq = awarenessRadius * awarenessRadius;
        Location botLoc = botEntity.getLocation();

        // Mark all threats as not-seen-this-tick
        for (ThreatEntry entry : threats.values()) {
            entry.visibleThisTick = false;
        }

        // Scan nearby entities for enemies
        for (Entity entity : botEntity.getNearbyEntities(awarenessRadius, awarenessRadius, awarenessRadius)) {
            if (!(entity instanceof Player)) continue;
            if (entity.getUniqueId().equals(botEntity.getUniqueId())) continue;
            if (entity.isDead()) continue;

            Player player = (Player) entity;
            double distSq = botLoc.distanceSquared(player.getLocation());
            if (distSq > awarenessRadiusSq) continue;

            UUID playerId = player.getUniqueId();
            ThreatEntry entry = threats.get(playerId);

            if (entry == null) {
                // New enemy detected
                entry = new ThreatEntry(playerId, player.getName());
                threats.put(playerId, entry);
            }

            // Update entry with current data
            Location prevLoc = entry.currentPosition;
            entry.currentPosition = player.getLocation().clone();
            entry.lastSeenTick = bot.getLocalTickCount();
            entry.visibleThisTick = true;
            entry.isVisible = true;

            // Estimate velocity from position delta
            if (prevLoc != null && prevLoc.getWorld() != null
                    && prevLoc.getWorld().equals(entry.currentPosition.getWorld())) {
                entry.velocity = entry.currentPosition.toVector().subtract(prevLoc.toVector());
            } else {
                entry.velocity = new Vector(0, 0, 0);
            }

            // Update predicted future position (10 ticks ahead)
            entry.predictedPosition = entry.currentPosition.clone().add(
                    entry.velocity.clone().multiply(10));

            // Update heat map
            addHeat(entry.currentPosition);
        }

        // Remove stale entries (not seen for > 200 ticks = 10 seconds)
        Iterator<Map.Entry<UUID, ThreatEntry>> iter = threats.entrySet().iterator();
        while (iter.hasNext()) {
            ThreatEntry entry = iter.next().getValue();
            if (!entry.visibleThisTick) {
                entry.isVisible = false;
                if (bot.getLocalTickCount() - entry.lastSeenTick > 200) {
                    iter.remove();
                }
            }
        }

        // Decay heat map
        decayHeatMap();
    }

    // ─── Queries ────────────────────────────────────────────────

    /**
     * Returns all currently visible enemies.
     *
     * @return list of visible threat entries
     */
    @Nonnull
    public List<ThreatEntry> getVisibleThreats() {
        List<ThreatEntry> visible = new ArrayList<>();
        for (ThreatEntry entry : threats.values()) {
            if (entry.isVisible) {
                visible.add(entry);
            }
        }
        return visible;
    }

    /**
     * Returns the nearest visible enemy to the bot.
     *
     * @return the nearest threat entry, or null if no enemies visible
     */
    @Nullable
    public ThreatEntry getNearestThreat() {
        Location botLoc = bot.getLocation();
        if (botLoc == null) return null;

        ThreatEntry nearest = null;
        double nearestDistSq = Double.MAX_VALUE;

        for (ThreatEntry entry : threats.values()) {
            if (!entry.isVisible || entry.currentPosition == null) continue;
            double distSq = botLoc.distanceSquared(entry.currentPosition);
            if (distSq < nearestDistSq) {
                nearestDistSq = distSq;
                nearest = entry;
            }
        }
        return nearest;
    }

    /**
     * Returns the number of visible enemies.
     *
     * @return visible enemy count
     */
    public int getVisibleEnemyCount() {
        int count = 0;
        for (ThreatEntry entry : threats.values()) {
            if (entry.isVisible) count++;
        }
        return count;
    }

    /**
     * Returns all tracked enemies (both visible and last-known positions).
     *
     * @return unmodifiable list of all threat entries
     */
    @Nonnull
    public List<ThreatEntry> getAllThreats() {
        return Collections.unmodifiableList(new ArrayList<>(threats.values()));
    }

    /**
     * Returns the threat entry for a specific player UUID.
     *
     * @param playerId the player's UUID
     * @return the threat entry, or null if not tracked
     */
    @Nullable
    public ThreatEntry getThreat(@Nonnull UUID playerId) {
        return threats.get(playerId);
    }

    /**
     * Returns the nearest visible enemy as a Bukkit Player entity.
     *
     * <p>This is a convenience method for systems (like BridgePathPlanner) that
     * need a direct Player reference rather than a ThreatEntry. It resolves the
     * nearest ThreatEntry to its actual Player object by searching nearby entities.</p>
     *
     * @return the nearest visible enemy Player, or null if none found
     */
    @Nullable
    public Player getNearestEnemy() {
        ThreatEntry nearest = getNearestThreat();
        if (nearest == null) return null;

        // Resolve the UUID to an actual Player entity
        LivingEntity botEntity = bot.getLivingEntity();
        if (botEntity == null) return null;

        // Search nearby entities for the matching player UUID
        double awarenessRadius = bot.getDifficultyProfile().getAwarenessRadius();
        for (Entity entity : botEntity.getNearbyEntities(awarenessRadius, awarenessRadius, awarenessRadius)) {
            if (entity instanceof Player && entity.getUniqueId().equals(nearest.playerId)) {
                return (Player) entity;
            }
        }

        return null;
    }

    /**
     * Returns all currently visible enemies as Bukkit Player entities.
     *
     * <p>Resolves each visible ThreatEntry to its actual Player object.
     * Entries that cannot be resolved (player logged off, too far) are excluded.</p>
     *
     * @return list of visible enemy Players (never null, may be empty)
     */
    @Nonnull
    public List<Player> getVisibleEnemyPlayers() {
        List<Player> result = new ArrayList<>();
        LivingEntity botEntity = bot.getLivingEntity();
        if (botEntity == null) return result;

        double awarenessRadius = bot.getDifficultyProfile().getAwarenessRadius();
        List<ThreatEntry> visible = getVisibleThreats();
        if (visible.isEmpty()) return result;

        // Build a set of visible UUIDs for quick lookup
        java.util.Set<UUID> visibleIds = new java.util.HashSet<>();
        for (ThreatEntry entry : visible) {
            visibleIds.add(entry.playerId);
        }

        // Single pass over nearby entities to resolve all visible threats
        for (Entity entity : botEntity.getNearbyEntities(awarenessRadius, awarenessRadius, awarenessRadius)) {
            if (entity instanceof Player && visibleIds.contains(entity.getUniqueId())) {
                result.add((Player) entity);
            }
        }

        return result;
    }

    /**
     * Returns the nearest enemy Player to a specific location.
     * Useful for checking threats near a bridge destination or other point of interest.
     *
     * @param location the reference location
     * @return the nearest visible enemy Player to that location, or null
     */
    @Nullable
    public Player getNearestEnemyTo(@Nonnull Location location) {
        List<Player> enemies = getVisibleEnemyPlayers();
        if (enemies.isEmpty()) return null;

        Player nearest = null;
        double nearestDistSq = Double.MAX_VALUE;

        for (Player enemy : enemies) {
            if (enemy.isDead() || !enemy.isValid()) continue;
            double distSq = location.distanceSquared(enemy.getLocation());
            if (distSq < nearestDistSq) {
                nearestDistSq = distSq;
                nearest = enemy;
            }
        }

        return nearest;
    }


    /**
     * Returns the heat level at a given location. Higher heat means more
     * enemy activity in the area.
     *
     * @param location the location to check
     * @return the heat level (0.0 = cold, higher = hotter)
     */
    public double getHeatAt(@Nonnull Location location) {
        long key = encodeHeatGrid(location);
        Double heat = heatMap.get(key);
        return heat != null ? heat : 0.0;
    }

    /**
     * Checks if a specific player is currently being tracked.
     *
     * @param playerId the player UUID
     * @return true if tracked
     */
    public boolean isTracking(@Nonnull UUID playerId) {
        return threats.containsKey(playerId);
    }

    /** Clears all tracked threats and heat map data. */
    public void clear() {
        threats.clear();
        heatMap.clear();
    }

    // ─── Heat Map ───────────────────────────────────────────────

    /**
     * Adds heat at the given location.
     *
     * @param location the location where enemy activity occurred
     */
    private void addHeat(@Nonnull Location location) {
        long key = encodeHeatGrid(location);
        double current = heatMap.getOrDefault(key, 0.0);
        heatMap.put(key, current + HEAT_INCREMENT);
    }

    /**
     * Decays all heat map values by the decay factor. Removes entries that
     * fall below a minimum threshold to prevent unbounded growth.
     */
    private void decayHeatMap() {
        Iterator<Map.Entry<Long, Double>> iter = heatMap.entrySet().iterator();
        while (iter.hasNext()) {
            Map.Entry<Long, Double> entry = iter.next();
            double newHeat = entry.getValue() * HEAT_DECAY;
            if (newHeat < 0.01) {
                iter.remove();
            } else {
                entry.setValue(newHeat);
            }
        }
    }

    /**
     * Encodes a location into a heat map grid cell key.
     *
     * @param location the location
     * @return the encoded grid cell key
     */
    private static long encodeHeatGrid(@Nonnull Location location) {
        int gx = location.getBlockX() / HEAT_GRID_SIZE;
        int gy = location.getBlockY() / HEAT_GRID_SIZE;
        int gz = location.getBlockZ() / HEAT_GRID_SIZE;
        return ((long) (gx & 0xFFFFF) << 40) | ((long) (gy & 0xFFF) << 20) | (gz & 0xFFFFF);
    }

    // ═════════════════════════════════════════════════════════════
    //  INNER: ThreatEntry
    // ═════════════════════════════════════════════════════════════

    /**
     * Data object holding tracking information for a single enemy.
     */
    public static class ThreatEntry {
        /** The enemy player's UUID. */
        public final UUID playerId;

        /** The enemy player's name. */
        public final String playerName;

        /** Current known position. */
        public Location currentPosition;

        /** Estimated velocity vector (blocks per tick). */
        public Vector velocity;

        /** Predicted future position (~10 ticks ahead). */
        public Location predictedPosition;

        /** The bot's local tick when this enemy was last seen. */
        public long lastSeenTick;

        /** Whether this enemy is currently visible. */
        public boolean isVisible;

        /** Internal flag: whether this enemy was seen during this tick's update. */
        boolean visibleThisTick;

        /**
         * Creates a new ThreatEntry.
         *
         * @param playerId   the enemy's UUID
         * @param playerName the enemy's name
         */
        public ThreatEntry(@Nonnull UUID playerId, @Nonnull String playerName) {
            this.playerId = playerId;
            this.playerName = playerName;
            this.currentPosition = null;
            this.velocity = new Vector(0, 0, 0);
            this.predictedPosition = null;
            this.lastSeenTick = 0;
            this.isVisible = false;
            this.visibleThisTick = false;
        }



        /**
         * Returns the distance from this threat to a location.
         *
         * @param loc the reference location
         * @return the distance, or Double.MAX_VALUE if position unknown
         */
        public double distanceTo(@Nonnull Location loc) {
            if (currentPosition == null || !loc.getWorld().equals(currentPosition.getWorld())) {
                return Double.MAX_VALUE;
            }
            return loc.distance(currentPosition);
        }

        /**
         * Returns the horizontal speed of this threat in blocks per tick.
         *
         * @return the horizontal speed
         */
        public double getHorizontalSpeed() {
            return Math.sqrt(velocity.getX() * velocity.getX() + velocity.getZ() * velocity.getZ());
        }

        @Override
        public String toString() {
            return "ThreatEntry{" + playerName
                    + ", visible=" + isVisible
                    + ", pos=" + (currentPosition != null
                    ? String.format("(%.1f,%.1f,%.1f)", currentPosition.getX(),
                    currentPosition.getY(), currentPosition.getZ())
                    : "null")
                    + "}";
        }
    }
}

