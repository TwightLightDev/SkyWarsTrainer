package org.twightlight.skywarstrainer.game;

import org.twightlight.skywarstrainer.SkyWarsTrainerPlugin;

import javax.annotation.Nonnull;

/**
 * Tracks the current phase of the SkyWars game.
 *
 * <p>The SkyWars game lifecycle consists of these phases:
 * <ol>
 *   <li>{@link Phase#PRE_GAME} — Players are in cages, waiting for the game to start.</li>
 *   <li>{@link Phase#GRACE_PERIOD} — Cages have opened; PvP is disabled; players loot.</li>
 *   <li>{@link Phase#ACTIVE} — Full game: PvP enabled, all systems active.</li>
 *   <li>{@link Phase#DEATHMATCH} — Final phase: players teleported to center, fight to the death.</li>
 *   <li>{@link Phase#END} — Game over. Winner determined. Cleanup pending.</li>
 * </ol></p>
 *
 * <p>The game state is primarily set by the SkyWars plugin hook (see Phase 6:
 * {@code SkyWarsGameHook}). In Phase 1, the state defaults to ACTIVE since the
 * hook is not yet implemented. External plugins or commands can set the phase
 * via {@link #setPhase(Phase)}.</p>
 *
 * <p>The game state also tracks elapsed time in the current phase and total
 * game time, which is used by {@code TimePressureConsideration} and
 * {@code GamePhaseTracker} in later phases.</p>
 */
public class GameState {

    private final SkyWarsTrainerPlugin plugin;

    /** The current game phase. */
    private Phase currentPhase;

    /**
     * Server tick when the current phase started. Used to calculate
     * time spent in phase.
     */
    private long phaseStartTick;

    /**
     * Server tick when the game started (cage release). Used to calculate
     * total game duration. -1 if game hasn't started yet.
     */
    private long gameStartTick;

    /**
     * A global tick counter incremented each server tick. This is used
     * by subsystems that need a consistent tick reference without relying
     * on Bukkit's scheduler task IDs.
     */
    private long globalTickCount;

    /**
     * Creates a new GameState tracker. Initializes to PRE_GAME phase.
     *
     * @param plugin the owning plugin instance
     */
    public GameState(@Nonnull SkyWarsTrainerPlugin plugin) {
        this.plugin = plugin;
        this.currentPhase = Phase.PRE_GAME;
        this.phaseStartTick = 0;
        this.gameStartTick = -1;
        this.globalTickCount = 0;
    }

    // ─── Phase Management ───────────────────────────────────────

    /**
     * Returns the current game phase.
     *
     * @return the current phase
     */
    @Nonnull
    public Phase getPhase() {
        return currentPhase;
    }

    /**
     * Sets the game phase. Logs the transition and updates timing references.
     *
     * <p>If transitioning to GRACE_PERIOD or ACTIVE for the first time,
     * {@code gameStartTick} is recorded.</p>
     *
     * @param newPhase the new phase
     */
    public void setPhase(@Nonnull Phase newPhase) {
        if (newPhase == currentPhase) return;

        Phase oldPhase = currentPhase;
        currentPhase = newPhase;
        phaseStartTick = globalTickCount;

        // Record game start time on first transition out of PRE_GAME
        if (oldPhase == Phase.PRE_GAME && gameStartTick < 0) {
            gameStartTick = globalTickCount;
        }

        plugin.getLogger().info("Game phase: " + oldPhase.name() + " -> " + newPhase.name());
    }

    /**
     * Advances the global tick counter. Called once per server tick by the
     * main tick loop. Subsystems can read the tick count for timing purposes.
     */
    public void tick() {
        globalTickCount++;
    }

    // ─── Phase Queries ──────────────────────────────────────────

    /** @return true if the game is in the pre-game (cage) phase */
    public boolean isPreGame() {
        return currentPhase == Phase.PRE_GAME;
    }

    /** @return true if the game is in the grace period (no PvP) */
    public boolean isGracePeriod() {
        return currentPhase == Phase.GRACE_PERIOD;
    }

    /** @return true if the game is in the active phase (PvP enabled) */
    public boolean isActive() {
        return currentPhase == Phase.ACTIVE;
    }

    /** @return true if the game is in deathmatch phase */
    public boolean isDeathmatch() {
        return currentPhase == Phase.DEATHMATCH;
    }

    /** @return true if the game has ended */
    public boolean isEnded() {
        return currentPhase == Phase.END;
    }

    /**
     * Returns true if PvP combat is allowed in the current phase.
     * PvP is allowed during ACTIVE and DEATHMATCH phases.
     *
     * @return true if PvP is enabled
     */
    public boolean isPvPEnabled() {
        return currentPhase == Phase.ACTIVE || currentPhase == Phase.DEATHMATCH;
    }

    /**
     * Returns true if bots should be actively running their AI loops.
     * Bots are active during GRACE_PERIOD, ACTIVE, and DEATHMATCH.
     *
     * @return true if bot AI should be running
     */
    public boolean isBotAIActive() {
        return currentPhase == Phase.GRACE_PERIOD
                || currentPhase == Phase.ACTIVE
                || currentPhase == Phase.DEATHMATCH;
    }

    // ─── Timing ─────────────────────────────────────────────────

    /**
     * Returns the number of ticks elapsed in the current phase.
     *
     * @return ticks in current phase
     */
    public long getTicksInCurrentPhase() {
        return globalTickCount - phaseStartTick;
    }

    /**
     * Returns the number of seconds elapsed in the current phase.
     *
     * @return seconds in current phase (approximate, based on 20 TPS)
     */
    public double getSecondsInCurrentPhase() {
        return getTicksInCurrentPhase() / 20.0;
    }

    /**
     * Returns the total number of ticks since the game started (cage release).
     * Returns 0 if the game hasn't started yet.
     *
     * @return total game ticks
     */
    public long getTotalGameTicks() {
        if (gameStartTick < 0) return 0;
        return globalTickCount - gameStartTick;
    }

    /**
     * Returns the total game time in seconds since cage release.
     *
     * @return total game seconds (approximate, based on 20 TPS)
     */
    public double getTotalGameSeconds() {
        return getTotalGameTicks() / 20.0;
    }

    /**
     * Returns the global tick counter. This is a monotonically increasing
     * value incremented once per server tick while the plugin is active.
     *
     * @return the current global tick count
     */
    public long getGlobalTickCount() {
        return globalTickCount;
    }

    /**
     * Resets the game state to PRE_GAME for a new game.
     * Clears all timing references.
     */
    public void reset() {
        currentPhase = Phase.PRE_GAME;
        phaseStartTick = globalTickCount;
        gameStartTick = -1;
        plugin.getLogger().info("Game state reset to PRE_GAME.");
    }

    // ═════════════════════════════════════════════════════════════
    //  INNER: Phase Enum
    // ═════════════════════════════════════════════════════════════

    /**
     * Enumeration of SkyWars game phases. Each phase has different rules
     * for what bots should and should not do.
     */
    public enum Phase {
        /**
         * Players are in cages waiting for the game to start.
         * Bots should stand still, look around, and pre-plan.
         */
        PRE_GAME,

        /**
         * Cages have opened. PvP is disabled. Players are looting.
         * Bots should start their opening strategy (loot or rush mid).
         */
        GRACE_PERIOD,

        /**
         * Full game in progress. PvP is enabled. All AI systems active.
         * This is where most gameplay happens.
         */
        ACTIVE,

        /**
         * Deathmatch phase. Players are typically teleported to center.
         * All bots prioritize combat. Time pressure is maximum.
         */
        DEATHMATCH,

        /**
         * Game has ended. Winner determined. Bots should stop AI,
         * play victory/defeat animations, and prepare for cleanup.
         */
        END
    }
}

