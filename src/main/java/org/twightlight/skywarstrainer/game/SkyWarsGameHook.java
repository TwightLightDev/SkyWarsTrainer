package org.twightlight.skywarstrainer.game;

import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.twightlight.skywars.api.server.SkyWarsServer;
import org.twightlight.skywars.api.server.SkyWarsState;
import org.twightlight.skywars.api.server.SkyWarsTeam;
import org.twightlight.skywars.arena.Arena;
import org.twightlight.skywars.arena.ArenaConfig;
import org.twightlight.skywars.arena.ui.enums.SkyWarsMode;
import org.twightlight.skywars.arena.ui.enums.SkyWarsType;
import org.twightlight.skywarstrainer.SkyWarsTrainerPlugin;
import org.twightlight.skywarstrainer.bot.TrainerBot;
import org.twightlight.skywarstrainer.util.MathUtil;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Level;
import java.util.stream.Collectors;

/**
 * Hard-hooks into the LostSkyWars plugin for direct arena integration.
 *
 * <p>This class provides direct access to LostSkyWars's Arena system,
 * allowing TrainerBots to join arenas as actual participants, read spawn
 * locations, chest positions, game state, alive players, and event timelines.</p>
 *
 * <p>Unlike a generic soft-hook, this reads directly from {@link Arena},
 * {@link SkyWarsTeam}, {@link ArenaConfig}, and {@link SkyWarsState}
 * from the {@code org.twightlight.skywars} package.</p>
 */
public class SkyWarsGameHook {

    private final SkyWarsTrainerPlugin plugin;

    /**
     * Creates a new SkyWarsGameHook.
     *
     * @param plugin the owning plugin
     */
    public SkyWarsGameHook(@Nonnull SkyWarsTrainerPlugin plugin) {
        this.plugin = plugin;
    }

    // ─── Arena Queries ──────────────────────────────────────────

    /**
     * Returns the current game phase for the given arena, mapped to our
     * internal {@link GameState.Phase} enum.
     *
     * @param arena the LostSkyWars arena
     * @return the corresponding game phase
     */
    @Nonnull
    public GameState.Phase getGamePhase(@Nonnull Arena<?> arena) {
        SkyWarsState state = arena.getState();
        if (state == null) return GameState.Phase.PRE_GAME;

        switch (state) {
            case NONE:
            case WAITING:
            case STARTING:
                return GameState.Phase.PRE_GAME;
            case INGAME:
                // Check if we're in the initial grace period (first ~5 seconds with damage resistance)
                // LostSkyWars doesn't have a separate grace state; players get noDamageTicks on start.
                // We detect grace period by checking if game timer is still very early.
                long gameTimeSec = getGameElapsedSeconds(arena);
                if (gameTimeSec < 5) {
                    return GameState.Phase.GRACE_PERIOD;
                }
                // Check if doom event is active (deathmatch equivalent)
                if (isDoomActive(arena)) {
                    return GameState.Phase.DEATHMATCH;
                }
                return GameState.Phase.ACTIVE;
            case ENDED:
            case ROLLBACKING:
                return GameState.Phase.END;
            default:
                return GameState.Phase.ACTIVE;
        }
    }

    /**
     * Checks if the arena is currently in an ingame state where PvP is active.
     *
     * @param arena the arena
     * @return true if the game is active and PvP should be happening
     */
    public boolean isPvPEnabled(@Nonnull Arena<?> arena) {
        return arena.getState() == SkyWarsState.INGAME;
    }

    /**
     * Returns the number of seconds elapsed since the game started.
     *
     * @param arena the arena
     * @return elapsed seconds, or 0 if not started
     */
    public long getGameElapsedSeconds(@Nonnull Arena<?> arena) {
        if (arena.getStartTimeMillis() <= 0) return 0;
        return (System.currentTimeMillis() - arena.getStartTimeMillis()) / 1000L;
    }

    /**
     * Checks if the doom (ender dragon) event is currently active in the arena.
     * Doom is the deathmatch phase of LostSkyWars.
     *
     * @param arena the arena
     * @return true if doom is active
     */
    public boolean isDoomActive(@Nonnull Arena<?> arena) {
        try {
            int eventTime = arena.getEventTime(false);
            if (arena.getTimeline().containsKey(eventTime)) {
                return arena.getTimeline().get(eventTime) ==
                        org.twightlight.skywars.arena.ui.enums.SkyWarsEvent.Doom;
            }
        } catch (Exception e) {
            // Timeline may not be accessible in all states
        }
        return false;
    }

    /**
     * Returns whether the arena is in a joinable waiting state.
     *
     * @param arena the arena
     * @return true if waiting for players
     */
    public boolean isWaiting(@Nonnull Arena<?> arena) {
        return arena.getState() != null && arena.getState().canJoin();
    }

    /**
     * Returns the total game timer value (countdown to events).
     *
     * @param arena the arena
     * @return timer value in seconds
     */
    public int getTimer(@Nonnull Arena<?> arena) {
        return arena.getTimer();
    }

    // ─── Player / Team Queries ──────────────────────────────────

    /**
     * Returns the number of alive players (real + bots) in the arena.
     *
     * @param arena the arena
     * @return alive count
     */
    public int getAliveCount(@Nonnull Arena<?> arena) {
        return arena.getAlive();
    }

    /**
     * Returns maximum player capacity of the arena.
     *
     * @param arena the arena
     * @return max player count
     */
    public int getMaxPlayers(@Nonnull Arena<?> arena) {
        return arena.getMaxPlayers();
    }

    /**
     * Returns all alive teams in the arena.
     *
     * @param arena the arena
     * @return list of alive teams
     */
    @Nonnull
    public List<SkyWarsTeam> getAliveTeams(@Nonnull Arena<?> arena) {
        try {
            return arena.getAliveTeams();
        } catch (Exception e) {
            return new ArrayList<>();
        }
    }

    /**
     * Returns all teams (including dead ones) in the arena.
     *
     * @param arena the arena
     * @return all teams
     */
    @Nonnull
    public List<SkyWarsTeam> getAllTeams(@Nonnull Arena<?> arena) {
        try {
            return arena.getTeams();
        } catch (Exception e) {
            return new ArrayList<>();
        }
    }

    /**
     * Returns the team a player belongs to.
     *
     * @param arena  the arena
     * @param player the player
     * @return the team, or null
     */
    @Nullable
    public SkyWarsTeam getTeam(@Nonnull Arena<?> arena, @Nonnull Player player) {
        return arena.getTeam(player);
    }

    /**
     * Gets all alive real players (non-bot) in the arena.
     *
     * @param arena the arena
     * @return list of alive players
     */
    @Nonnull
    public List<Player> getAlivePlayers(@Nonnull Arena<?> arena) {
        try {
            return arena.getPlayers(false);
        } catch (Exception e) {
            return new ArrayList<>();
        }
    }

    /**
     * Gets all players (including spectators) in the arena.
     *
     * @param arena the arena
     * @return list of all players
     */
    @Nonnull
    public List<Player> getAllPlayers(@Nonnull Arena<?> arena) {
        try {
            return arena.getPlayers(true);
        } catch (Exception e) {
            return new ArrayList<>();
        }
    }

    /**
     * Checks if a player is alive in the arena.
     *
     * @param arena  the arena
     * @param player the player
     * @return true if alive
     */
    public boolean isAlive(@Nonnull Arena<?> arena, @Nonnull Player player) {
        return arena.isAlive(player);
    }

    /**
     * Checks if a player is a spectator in the arena.
     *
     * @param arena  the arena
     * @param player the player
     * @return true if spectating
     */
    public boolean isSpectator(@Nonnull Arena<?> arena, @Nonnull Player player) {
        return arena.isSpectator(player);
    }

    // ─── Spawn & Location Queries ───────────────────────────────

    /**
     * Returns all spawn locations for the given arena. Each spawn corresponds
     * to a team slot.
     *
     * @param arena the arena
     * @return list of spawn locations
     */
    @Nonnull
    public List<Location> getSpawnLocations(@Nonnull Arena<?> arena) {
        List<Location> locations = new ArrayList<>();
        try {
            for (SkyWarsTeam team : arena.getTeams()) {
                Location loc = team.getLocation();
                if (loc != null) {
                    locations.add(loc.clone().add(0, 1, 0)); // Offset up to avoid being inside blocks
                }
            }
        } catch (Exception e) {
            plugin.getLogger().log(Level.WARNING, "Error getting spawn locations", e);
        }
        return locations;
    }

    /**
     * Returns available (empty) spawn locations — team slots with no players.
     *
     * @param arena the arena
     * @return list of available spawn locations
     */
    @Nonnull
    public List<Location> getAvailableSpawnLocations(@Nonnull Arena<?> arena) {
        List<Location> locations = new ArrayList<>();
        try {
            for (SkyWarsTeam team : arena.getTeams()) {
                if (team.canJoin()) {
                    Location loc = team.getLocation();
                    if (loc != null) {
                        locations.add(loc.clone().add(0, 1, 0));
                    }
                }
            }
        } catch (Exception e) {
            plugin.getLogger().log(Level.WARNING, "Error getting available spawns", e);
        }
        return locations;
    }

    /**
     * Returns the first available team slot in the arena for bot assignment.
     *
     * @param arena the arena
     * @return the team, or null if full
     */
    @Nullable
    public SkyWarsTeam getAvailableTeam(@Nonnull Arena<?> arena) {
        for (SkyWarsTeam team : arena.getTeams()) {
            if (team.canJoin()) {
                return team;
            }
        }
        return null;
    }

    // ─── Map Queries ────────────────────────────────────────────

    /**
     * Returns the map name of the arena.
     *
     * @param arena the arena
     * @return the map name
     */
    @Nonnull
    public String getMapName(@Nonnull Arena<?> arena) {
        return arena.getName() != null ? arena.getName() : "Unknown";
    }

    /**
     * Returns the game mode (SOLO or DOUBLES).
     *
     * @param arena the arena
     * @return the game mode
     */
    @Nonnull
    public SkyWarsMode getMode(@Nonnull Arena<?> arena) {
        return arena.getMode();
    }

    /**
     * Returns the game type (NORMAL, INSANE, RANKED, DUELS).
     *
     * @param arena the arena
     * @return the game type
     */
    @Nonnull
    public SkyWarsType getType(@Nonnull Arena<?> arena) {
        return arena.getType();
    }

    /**
     * Returns the arena's server name (internal identifier).
     *
     * @param arena the arena
     * @return server name
     */
    @Nonnull
    public String getServerName(@Nonnull Arena<?> arena) {
        return arena.getServerName() != null ? arena.getServerName() : "unknown";
    }

    // ─── Arena Lookup ───────────────────────────────────────────

    /**
     * Finds an arena by its server name.
     *
     * @param serverName the internal server name
     * @return the arena, or null if not found
     */
    @Nullable
    public Arena<?> findArena(@Nonnull String serverName) {
        return Arena.getByWorldName(serverName);
    }

    /**
     * Lists all available arenas.
     *
     * @return all arenas
     */
    @Nonnull
    public List<Arena<?>> listArenas() {
        return new ArrayList<>(Arena.listServers());
    }

    /**
     * Lists arenas that are in waiting state and have room for bots.
     *
     * @return joinable arenas
     */
    @Nonnull
    public List<Arena<?>> listJoinableArenas() {
        return Arena.listServers().stream()
                .filter(a -> a.getState() != null && a.getState().canJoin()
                        && a.getAlive() < a.getMaxPlayers())
                .collect(Collectors.toList());
    }

    /**
     * Finds a random joinable arena with the given mode.
     *
     * @param mode the game mode (SOLO / DOUBLES)
     * @param type the game type (NORMAL / INSANE / RANKED)
     * @return a random arena, or null
     */
    @Nullable
    public Arena<?> findRandomArena(@Nonnull SkyWarsMode mode, @Nonnull SkyWarsType type) {
        return Arena.findRandom(mode, type);
    }

    // ─── Kill Tracking ──────────────────────────────────────────

    /**
     * Returns the kill count for a player in the arena.
     *
     * @param arena  the arena
     * @param player the player
     * @return kill count
     */
    public int getKills(@Nonnull Arena<?> arena, @Nonnull Player player) {
        return arena.getKills(player);
    }
}
