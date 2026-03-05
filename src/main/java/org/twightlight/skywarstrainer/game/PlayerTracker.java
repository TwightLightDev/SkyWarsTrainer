package org.twightlight.skywarstrainer.game;

import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.twightlight.skywarstrainer.SkyWarsTrainerPlugin;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Tracks players in the current SkyWars game: who is alive, who has died,
 * and the kill feed. This information feeds into the bot's decision engine
 * (e.g., {@code PlayerCountConsideration}, threat evaluation).
 *
 * <p>This class listens for Bukkit events to maintain its tracking state.
 * It differentiates between real players and bot NPCs (which are tracked
 * separately by {@link org.twightlight.skywarstrainer.bot.BotManager}).</p>
 *
 * <p>Thread safety: uses ConcurrentHashMap because events may fire from
 * different contexts, and bot AI may read tracking data from the tick loop.</p>
 */
public class PlayerTracker implements Listener {

    private final SkyWarsTrainerPlugin plugin;

    /**
     * Set of UUIDs of players currently alive in the game.
     * Key: player UUID, Value: player name (for display/logging).
     */
    private final Map<UUID, String> alivePlayers;

    /**
     * Ordered list of kill feed entries. Newest entries at the end.
     * Each entry records who died, who killed them, and the timestamp.
     */
    private final List<KillEntry> killFeed;

    /**
     * Maximum kill feed entries to keep in memory. Older entries are
     * discarded to prevent unbounded growth in long games.
     */
    private static final int MAX_KILL_FEED_SIZE = 50;

    /**
     * Creates a new PlayerTracker for the given plugin.
     *
     * @param plugin the owning plugin instance
     */
    public PlayerTracker(@Nonnull SkyWarsTrainerPlugin plugin) {
        this.plugin = plugin;
        this.alivePlayers = new ConcurrentHashMap<>();
        this.killFeed = Collections.synchronizedList(new ArrayList<KillEntry>());
    }

    // ─── Player Registration ────────────────────────────────────

    /**
     * Registers a player as alive in the game. Called when a game starts
     * or when a player joins a game in progress.
     *
     * @param player the player to register
     */
    public void registerAlive(@Nonnull Player player) {
        alivePlayers.put(player.getUniqueId(), player.getName());
        if (plugin.getConfigManager().isDebugMode()) {
            plugin.getLogger().info("[DEBUG] PlayerTracker: registered alive: " + player.getName());
        }
    }

    /**
     * Registers a player as alive by UUID and name. Used for registering
     * bot NPCs that may not have a real Player object at registration time.
     *
     * @param uuid the entity UUID
     * @param name the display name
     */
    public void registerAlive(@Nonnull UUID uuid, @Nonnull String name) {
        alivePlayers.put(uuid, name);
    }

    /**
     * Marks a player as dead. Removes them from the alive set and records
     * the death in the kill feed.
     *
     * @param deadUuid   the UUID of the dead player
     * @param deadName   the name of the dead player
     * @param killerUuid the UUID of the killer (null if environmental death)
     * @param killerName the name of the killer (null if environmental death)
     */
    public void registerDeath(@Nonnull UUID deadUuid, @Nonnull String deadName,
                              @Nullable UUID killerUuid, @Nullable String killerName) {
        alivePlayers.remove(deadUuid);

        KillEntry entry = new KillEntry(deadUuid, deadName, killerUuid, killerName, System.currentTimeMillis());
        killFeed.add(entry);

        // Trim kill feed if it exceeds maximum size
        while (killFeed.size() > MAX_KILL_FEED_SIZE) {
            killFeed.remove(0);
        }

        if (plugin.getConfigManager().isDebugMode()) {
            String killerInfo = killerName != null ? killerName : "environment";
            plugin.getLogger().info("[DEBUG] PlayerTracker: death: " + deadName + " killed by " + killerInfo
                    + " (alive: " + alivePlayers.size() + ")");
        }
    }

    /**
     * Removes a player from tracking entirely (e.g., they disconnected).
     *
     * @param uuid the player UUID
     */
    public void unregister(@Nonnull UUID uuid) {
        alivePlayers.remove(uuid);
    }

    // ─── Queries ────────────────────────────────────────────────

    /**
     * Returns the number of players currently alive.
     *
     * @return alive player count
     */
    public int getAliveCount() {
        return alivePlayers.size();
    }

    /**
     * Returns true if the given player is currently alive.
     *
     * @param uuid the player UUID
     * @return true if alive
     */
    public boolean isAlive(@Nonnull UUID uuid) {
        return alivePlayers.containsKey(uuid);
    }

    /**
     * Returns the name of an alive player by UUID.
     *
     * @param uuid the player UUID
     * @return the player name, or null if not alive/tracked
     */
    @Nullable
    public String getAliveName(@Nonnull UUID uuid) {
        return alivePlayers.get(uuid);
    }

    /**
     * Returns an unmodifiable snapshot of all alive player UUIDs.
     *
     * @return set of alive player UUIDs
     */
    @Nonnull
    public List<UUID> getAlivePlayerUUIDs() {
        return new ArrayList<>(alivePlayers.keySet());
    }

    /**
     * Returns an unmodifiable snapshot of all alive player names.
     *
     * @return list of alive player names
     */
    @Nonnull
    public List<String> getAlivePlayerNames() {
        return new ArrayList<>(alivePlayers.values());
    }

    /**
     * Returns the kill feed as a list of entries, newest last.
     *
     * @return the kill feed
     */
    @Nonnull
    public List<KillEntry> getKillFeed() {
        return new ArrayList<>(killFeed);
    }

    /**
     * Returns the most recent kill feed entry, or null if the feed is empty.
     *
     * @return the latest kill entry
     */
    @Nullable
    public KillEntry getLatestKill() {
        if (killFeed.isEmpty()) return null;
        return killFeed.get(killFeed.size() - 1);
    }

    /**
     * Returns the total number of deaths recorded in this game.
     *
     * @return total death count
     */
    public int getTotalDeaths() {
        return killFeed.size();
    }

    /**
     * Returns the number of kills attributed to a specific player.
     *
     * @param killerUuid the killer's UUID
     * @return their kill count
     */
    public int getKillCount(@Nonnull UUID killerUuid) {
        int count = 0;
        synchronized (killFeed) {
            for (KillEntry entry : killFeed) {
                if (killerUuid.equals(entry.getKillerUuid())) {
                    count++;
                }
            }
        }
        return count;
    }

    /**
     * Clears all tracking data. Called when a new game starts.
     */
    public void reset() {
        alivePlayers.clear();
        killFeed.clear();
        plugin.getLogger().info("PlayerTracker reset.");
    }

    // ─── Event Handlers ─────────────────────────────────────────

    /**
     * Handles player death events. Records the death in the kill feed
     * and removes the player from the alive set.
     */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPlayerDeath(PlayerDeathEvent event) {
        Player dead = event.getEntity();
        Player killer = dead.getKiller();

        registerDeath(
                dead.getUniqueId(),
                dead.getName(),
                killer != null ? killer.getUniqueId() : null,
                killer != null ? killer.getName() : null
        );
    }

    /**
     * Handles player quit events. Removes the player from tracking
     * to prevent stale entries.
     */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerQuit(PlayerQuitEvent event) {
        unregister(event.getPlayer().getUniqueId());
    }

    // ═════════════════════════════════════════════════════════════
    //  INNER: KillEntry
    // ═════════════════════════════════════════════════════════════

    /**
     * An immutable record of a death event in the kill feed.
     */
    public static final class KillEntry {

        private final UUID deadUuid;
        private final String deadName;
        private final UUID killerUuid;   // null for environmental deaths
        private final String killerName; // null for environmental deaths
        private final long timestampMs;

        public KillEntry(@Nonnull UUID deadUuid, @Nonnull String deadName,
                         @Nullable UUID killerUuid, @Nullable String killerName,
                         long timestampMs) {
            this.deadUuid = deadUuid;
            this.deadName = deadName;
            this.killerUuid = killerUuid;
            this.killerName = killerName;
            this.timestampMs = timestampMs;
        }

        @Nonnull public UUID getDeadUuid() { return deadUuid; }
        @Nonnull public String getDeadName() { return deadName; }
        @Nullable public UUID getKillerUuid() { return killerUuid; }
        @Nullable public String getKillerName() { return killerName; }
        public long getTimestampMs() { return timestampMs; }

        /**
         * Returns true if this was a player-caused death (not environmental).
         *
         * @return true if a killer was recorded
         */
        public boolean hasKiller() {
            return killerUuid != null;
        }

        @Override
        public String toString() {
            if (hasKiller()) {
                return deadName + " was killed by " + killerName;
            }
            return deadName + " died";
        }
    }
}
