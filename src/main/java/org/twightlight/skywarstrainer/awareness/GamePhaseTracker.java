package org.twightlight.skywarstrainer.awareness;

import org.twightlight.skywarstrainer.SkyWarsTrainerPlugin;
import org.twightlight.skywarstrainer.bot.TrainerBot;
import org.twightlight.skywarstrainer.game.GameState;
import org.twightlight.skywarstrainer.game.PlayerTracker;
import org.twightlight.skywarstrainer.util.MathUtil;

import javax.annotation.Nonnull;

/**
 * Tracks the conceptual "game phase" from the bot's perspective: early game,
 * mid game, or late game. This is distinct from the server's game state
 * ({@link GameState.Phase}) — the server phase is about mechanics (grace period,
 * PvP enabled, deathmatch), while the game phase is about strategic context
 * (should I loot, should I fight, how aggressive should I be?).
 *
 * <p>Game phases are determined by a combination of:
 * <ul>
 *   <li>Elapsed game time (seconds since cage release)</li>
 *   <li>Number of players still alive</li>
 *   <li>The server's game phase (e.g., deathmatch overrides to LATE)</li>
 *   <li>The bot's own equipment level (a well-geared bot may transition sooner)</li>
 * </ul></p>
 *
 * <p>These phases are used by:
 * <ul>
 *   <li>{@code TimePressureConsideration}: higher pressure in later phases</li>
 *   <li>{@code LootPriorityTable}: different item priorities per phase</li>
 *   <li>{@code DecisionEngine}: different utility weights per phase</li>
 *   <li>Personality behaviors: some personalities change behavior based on phase</li>
 * </ul></p>
 *
 * <p>Phase definitions:
 * <ul>
 *   <li><strong>EARLY (0-60s)</strong>: Looting, equipping, initial bridging.
 *       Focus on gathering resources.</li>
 *   <li><strong>MID (60-180s)</strong>: Transition to combat. Players are geared.
 *       Skirmishes and territorial control.</li>
 *   <li><strong>LATE (180s+)</strong>: Most players dead or fully geared.
 *       Aggressive plays, endgame pushes.</li>
 * </ul></p>
 */
public class GamePhaseTracker {

    private final TrainerBot bot;

    /** The current detected game phase. */
    private GamePhase currentPhase;

    /**
     * A continuous 0.0-1.0 progress value representing how far into the game
     * we are. 0.0 = game just started, 1.0 = deep late game / deathmatch.
     * Subsystems can use this for smooth scaling instead of discrete phase checks.
     */
    private double gameProgress;

    /**
     * Time thresholds for phase transitions (in seconds).
     * These are soft boundaries — player count also influences transitions.
     */
    private static final double EARLY_TO_MID_TIME = 60.0;
    private static final double MID_TO_LATE_TIME = 180.0;

    /**
     * Player count thresholds. If the alive player count drops below these
     * fractions of the starting count, the phase transitions earlier.
     * For example, if 50% of players are dead, we may transition to MID even
     * before 60 seconds.
     */
    private static final double MID_TRANSITION_ALIVE_FRACTION = 0.7;
    private static final double LATE_TRANSITION_ALIVE_FRACTION = 0.35;

    /**
     * Creates a new GamePhaseTracker for the given bot.
     *
     * @param bot the owning trainer bot
     */
    public GamePhaseTracker(@Nonnull TrainerBot bot) {
        this.bot = bot;
        this.currentPhase = GamePhase.EARLY;
        this.gameProgress = 0.0;
    }

    /**
     * Updates the game phase based on current conditions. Should be called
     * every 20-40 ticks (1-2 seconds) for responsive phase tracking
     * without excessive computation.
     */
    public void update() {
        SkyWarsTrainerPlugin plugin = SkyWarsTrainerPlugin.getInstance();
        if (plugin == null) return;

        GameState gameState = plugin.getGameState();
        PlayerTracker playerTracker = plugin.getPlayerTracker();

        // Override: deathmatch is always LATE
        if (gameState.isDeathmatch()) {
            currentPhase = GamePhase.LATE;
            gameProgress = 1.0;
            return;
        }

        // Override: pre-game and grace period are always EARLY
        if (gameState.isPreGame() || gameState.isGracePeriod()) {
            currentPhase = GamePhase.EARLY;
            gameProgress = 0.0;
            return;
        }

        // Override: game ended
        if (gameState.isEnded()) {
            currentPhase = GamePhase.LATE;
            gameProgress = 1.0;
            return;
        }

        // Calculate time-based progress
        double gameSeconds = gameState.getTotalGameSeconds();
        double timeProgress = MathUtil.clamp(gameSeconds / MID_TO_LATE_TIME, 0.0, 1.0);

        // Calculate player-count-based progress
        int totalPlayers = playerTracker.getInitialPlayerCount();
        int alivePlayers = playerTracker.getAliveCount();
        double playerProgress = 0.0;
        if (totalPlayers > 1) {
            double aliveFraction = (double) alivePlayers / totalPlayers;
            /*
             * Map alive fraction to progress:
             * 1.0 alive → 0.0 progress
             * 0.35 alive → 1.0 progress
             * This means as players die, progress increases.
             */
            playerProgress = MathUtil.clamp(
                    MathUtil.inverseLerp(1.0, LATE_TRANSITION_ALIVE_FRACTION, aliveFraction),
                    0.0, 1.0);
        }

        // Combine: use the HIGHER of time-based or player-based progress.
        // This ensures the phase advances if either condition is met.
        gameProgress = Math.max(timeProgress, playerProgress);

        // Determine discrete phase from continuous progress
        if (gameProgress < 0.33) {
            currentPhase = GamePhase.EARLY;
        } else if (gameProgress < 0.67) {
            currentPhase = GamePhase.MID;
        } else {
            currentPhase = GamePhase.LATE;
        }

        // Special case: 1v1 situation is always LATE regardless of time
        if (alivePlayers <= 2) {
            currentPhase = GamePhase.LATE;
            gameProgress = Math.max(gameProgress, 0.9);
        }
    }

    // ─── Queries ────────────────────────────────────────────────

    /**
     * Returns the current game phase.
     *
     * @return the game phase
     */
    @Nonnull
    public GamePhase getPhase() {
        return currentPhase;
    }

    /**
     * Returns the continuous game progress value.
     *
     * <p>This is preferred over discrete phase checks for utility scoring
     * because it provides smooth scaling. For example, a utility weight
     * that increases with game progress can use: {@code weight * getGameProgress()}</p>
     *
     * @return the game progress in [0.0, 1.0]
     */
    public double getGameProgress() {
        return gameProgress;
    }

    /**
     * Returns whether the game is in the early phase.
     *
     * @return true if early game
     */
    public boolean isEarlyGame() {
        return currentPhase == GamePhase.EARLY;
    }

    /**
     * Returns whether the game is in the mid phase.
     *
     * @return true if mid game
     */
    public boolean isMidGame() {
        return currentPhase == GamePhase.MID;
    }

    /**
     * Returns whether the game is in the late phase.
     *
     * @return true if late game
     */
    public boolean isLateGame() {
        return currentPhase == GamePhase.LATE;
    }

    /**
     * Returns a "time pressure" value from 0.0 (no pressure, early game)
     * to 1.0 (maximum pressure, deathmatch imminent). This directly feeds
     * into the TimePressureConsideration for the utility AI.
     *
     * <p>Time pressure increases non-linearly: slow rise early, accelerating
     * in late game. This pushes bots toward more aggressive play as the game
     * progresses.</p>
     *
     * @return the time pressure in [0.0, 1.0]
     */
    public double getTimePressure() {
        /*
         * Use a polynomial curve to make pressure increase slowly at first
         * and then spike in late game. power=2.0 gives a quadratic curve.
         */
        return MathUtil.polynomialCurve(gameProgress, 2.0);
    }

    /**
     * Returns the combat urgency factor. This is 0.0 in early game (looting is
     * preferred), ramps up through mid game, and reaches 1.0 in late game.
     * Used by the DecisionEngine to bias toward FIGHT/HUNT actions.
     *
     * @return the combat urgency in [0.0, 1.0]
     */
    public double getCombatUrgency() {
        if (currentPhase == GamePhase.EARLY) {
            // In early game, combat urgency is low but not zero
            // (enemy rushing you still demands combat)
            return MathUtil.clamp(gameProgress * 1.5, 0.0, 0.3);
        } else if (currentPhase == GamePhase.MID) {
            return MathUtil.lerp(0.3, 0.7, (gameProgress - 0.33) / 0.34);
        } else {
            return MathUtil.lerp(0.7, 1.0, (gameProgress - 0.67) / 0.33);
        }
    }

    /**
     * Returns the looting urgency factor. Inverse of combat urgency:
     * high in early game, low in late game.
     *
     * @return the looting urgency in [0.0, 1.0]
     */
    public double getLootingUrgency() {
        return MathUtil.clamp(1.0 - getCombatUrgency(), 0.0, 1.0);
    }

    /**
     * Returns the elapsed game time in seconds. Convenience accessor that
     * reads from the global GameState.
     *
     * @return game time in seconds, or 0 if the game hasn't started
     */
    public double getGameTimeSeconds() {
        SkyWarsTrainerPlugin plugin = SkyWarsTrainerPlugin.getInstance();
        if (plugin == null) return 0.0;
        return plugin.getGameState().getTotalGameSeconds();
    }

    // ═════════════════════════════════════════════════════════════
    //  INNER: GamePhase Enum
    // ═════════════════════════════════════════════════════════════

    /**
     * Conceptual game phases from the bot's strategic perspective.
     *
     * <p>These are independent of the server's {@link GameState.Phase} —
     * the server phase is about game rules (PvP on/off, deathmatch),
     * while this is about strategy (should I loot or fight?).</p>
     */
    public enum GamePhase {
        /**
         * Early game (first ~60 seconds or while most players are alive).
         * Primary focus: looting, equipping, initial positioning.
         * Combat only if forced (enemy rushes to your island).
         */
        EARLY,

        /**
         * Mid game (~60-180 seconds or after some players have died).
         * Transition period: finish looting, start hunting or defending.
         * Skirmishes for territory control. Bridge building.
         */
        MID,

        /**
         * Late game (180+ seconds, few players remaining, or deathmatch).
         * Full combat mode. Hunt remaining enemies. All-in fights.
         * Loot only if desperately needed.
         */
        LATE
    }
}

