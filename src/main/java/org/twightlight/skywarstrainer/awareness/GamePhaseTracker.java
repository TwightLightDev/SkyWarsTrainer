package org.twightlight.skywarstrainer.awareness;

import org.twightlight.skywars.api.server.SkyWarsState;
import org.twightlight.skywars.arena.Arena;
import org.twightlight.skywarstrainer.bot.TrainerBot;
import org.twightlight.skywarstrainer.util.MathUtil;

import javax.annotation.Nonnull;

/**
 * Tracks the bot's strategic game phase (EARLY / MID / LATE) by reading directly
 * from the LostSkyWars {@link Arena} object associated with this bot's game.
 *
 * <h3>Why rewritten from scratch?</h3>
 * <p>The previous implementation called {@code SkyWarsTrainerPlugin.getInstance().getGameState()}
 * and {@code .getPlayerTracker()}, neither of which exist on the plugin class. The plugin
 * is a thin coordinator — all game-specific state lives inside the {@link Arena} that the
 * bot was spawned into. This version reads {@link Arena#getState()}, {@link Arena#getAlive()},
 * {@link Arena#getInitialPlayers()}, and {@link Arena#getStartTimeMillis()} directly, making
 * GamePhaseTracker fully self-contained and independent of any wrapper classes.</p>
 *
 * <h3>Phase definitions (strategic, not mechanical)</h3>
 * <ul>
 *   <li><b>EARLY</b> (0–60 s or &gt;70 % players alive): loot, equip, initial positioning.</li>
 *   <li><b>MID</b>  (60–180 s or 35–70 % players alive): skirmishes, bridging, territory.</li>
 *   <li><b>LATE</b> (180 s+ or &lt;35 % players alive, or deathmatch): full combat.</li>
 * </ul>
 *
 * <h3>Deathmatch detection</h3>
 * <p>LostSkyWars fires a {@code SkyWarsDoomEvent} when the border/doom mechanic starts.
 * We detect this heuristically: if &le;2 players remain AND the game has been running
 * more than 60 s, we treat it as a deathmatch-equivalent (LATE phase, max pressure).</p>
 */
public final class GamePhaseTracker {

    // ─── Constants ────────────────────────────────────────────────────────────────

    /** Seconds at which EARLY transitions to MID (time-based). */
    private static final double EARLY_TO_MID_SECONDS  = 60.0;
    /** Seconds at which MID transitions to LATE (time-based). */
    private static final double MID_TO_LATE_SECONDS   = 180.0;

    /**
     * If the fraction of alive players drops below this value, force at least MID phase
     * regardless of elapsed time. 0.70 means if 30 % of players are dead → MID starts.
     */
    private static final double MID_PLAYER_FRACTION   = 0.70;
    /**
     * If the fraction of alive players drops below this, force LATE phase.
     * 0.35 means if 65 % are dead → LATE starts.
     */
    private static final double LATE_PLAYER_FRACTION  = 0.35;

    // ─── State ───────────────────────────────────────────────────────────────────

    private final TrainerBot bot;

    /** The resolved strategic game phase. Updated every call to {@link #update()}. */
    private GamePhase currentPhase = GamePhase.EARLY;

    /**
     * Continuous progress value in [0.0, 1.0].
     * 0 = game just started, 1 = deep late-game / deathmatch.
     * Preferred over the discrete phase for smooth utility scoring.
     */
    private double gameProgress = 0.0;

    /**
     * Whether we have previously observed the arena in INGAME state.
     * Used to latch the "game has started" condition so we only record
     * {@link #ingameStartedTimeMs} once.
     */
    private boolean ingameEverStarted = false;

    /**
     * Wall-clock timestamp (ms) when the arena first entered INGAME state.
     * 0 before the game starts. Used to compute elapsed in-game time
     * without touching {@code Arena#startTimeMillis} (which is protected).
     */
    private long ingameStartedTimeMs = 0L;

    /**
     * The last-seen initial player count. Cached so we don't keep calling
     * {@code arena.getInitialPlayers().size()} every tick (it iterates a list).
     */
    private int cachedInitialPlayerCount = -1;

    // ─── Constructor ─────────────────────────────────────────────────────────────

    /**
     * Creates a new GamePhaseTracker for the given bot.
     *
     * @param bot the owning trainer bot; its arena is read on every {@link #update()} call
     */
    public GamePhaseTracker(@Nonnull TrainerBot bot) {
        this.bot = bot;
    }

    // ─── Update ──────────────────────────────────────────────────────────────────

    /**
     * Recomputes {@link #currentPhase} and {@link #gameProgress}.
     *
     * <p>Should be called every 20–40 ticks (1–2 seconds) by the bot's tick loop.
     * Calling more often is safe but wasteful; calling less often may lag phase
     * transitions by a few seconds, which is acceptable.</p>
     */
    public void update() {
        Arena<?> arena = bot.getArena();
        SkyWarsState arenaState = arena.getState();

        // ── Latch INGAME start time ───────────────────────────────────────────
        if (arenaState == SkyWarsState.INGAME && !ingameEverStarted) {
            ingameEverStarted = true;
            long arenaStart = arena.getStartTimeMillis();
            ingameStartedTimeMs = (arenaStart > 0L) ? arenaStart : System.currentTimeMillis();
        }

        // ── Terminal / pre-game states ────────────────────────────────────────
        if (arenaState == SkyWarsState.ENDED || arenaState == SkyWarsState.ROLLBACKING) {
            currentPhase = GamePhase.LATE;
            gameProgress  = 1.0;
            return;
        }

        if (arenaState == SkyWarsState.WAITING || arenaState == SkyWarsState.STARTING) {
            currentPhase  = GamePhase.EARLY;
            gameProgress  = 0.0;
            return;
        }

        // ── INGAME: compute elapsed time ──────────────────────────────────────
        double elapsedSeconds = 0.0;
        if (ingameStartedTimeMs > 0L) {
            elapsedSeconds = (System.currentTimeMillis() - ingameStartedTimeMs) / 1000.0;
        }

        // ── Compute time-based progress ───────────────────────────────────────
        // Maps [0, MID_TO_LATE_SECONDS] → [0.0, 1.0]
        double timeProgress = MathUtil.clamp(elapsedSeconds / MID_TO_LATE_SECONDS, 0.0, 1.0);

        // ── Compute player-count-based progress ───────────────────────────────
        double playerProgress = computePlayerProgress(arena);

        // Use whichever metric is HIGHER — either condition can accelerate the phase.
        gameProgress = Math.max(timeProgress, playerProgress);

        // ── Map continuous progress → discrete phase ──────────────────────────
        if (gameProgress < 0.33) {
            currentPhase = GamePhase.EARLY;
        } else if (gameProgress < 0.67) {
            currentPhase = GamePhase.MID;
        } else {
            currentPhase = GamePhase.LATE;
        }

        // ── Hard override: 1v1 (or last 2 alive) is always LATE ──────────────
        int alive = arena.getAlive();
        if (alive <= 2 && ingameEverStarted) {
            currentPhase  = GamePhase.LATE;
            gameProgress  = Math.max(gameProgress, 0.9);
        }
    }

    /**
     * Computes a [0.0, 1.0] progress value based on how many players have died.
     *
     * <p>The mapping is:
     * <ul>
     *   <li>100 % alive → 0.0 progress</li>
     *   <li>{@value #LATE_PLAYER_FRACTION} alive → 1.0 progress</li>
     * </ul>
     * So as the server loses players, this value climbs toward 1.</p>
     */
    private double computePlayerProgress(@Nonnull Arena<?> arena) {
        // Lazy-initialise the initial player count (stable after game starts)
        if (cachedInitialPlayerCount < 1 && !arena.getInitialPlayers().isEmpty()) {
            cachedInitialPlayerCount = arena.getInitialPlayers().size();
        }
        if (cachedInitialPlayerCount < 1) {
            return 0.0; // Not enough information yet
        }

        double aliveFraction = (double) arena.getAlive() / cachedInitialPlayerCount;
        /*
         * inverseLerp(1.0, LATE_PLAYER_FRACTION, aliveFraction):
         *   when aliveFraction = 1.0  → returns 0.0
         *   when aliveFraction = LATE → returns 1.0
         *   in between               → linear interpolation
         */
        return MathUtil.clamp(
                MathUtil.inverseLerp(1.0, LATE_PLAYER_FRACTION, aliveFraction),
                0.0, 1.0
        );
    }

    // ─── Queries ─────────────────────────────────────────────────────────────────

    /**
     * Returns the current strategic game phase.
     *
     * @return the current phase (never null)
     */
    @Nonnull
    public GamePhase getPhase() {
        return currentPhase;
    }

    /**
     * Returns the continuous game-progress value in [0.0, 1.0].
     *
     * <p>Preferred over {@link #getPhase()} for utility AI scoring because it
     * provides smooth, fine-grained scaling instead of discrete jumps.</p>
     *
     * @return game progress [0.0, 1.0]
     */
    public double getGameProgress() {
        return gameProgress;
    }

    /** @return {@code true} if the phase is {@link GamePhase#EARLY} */
    public boolean isEarlyGame() {
        return currentPhase == GamePhase.EARLY;
    }

    /** @return {@code true} if the phase is {@link GamePhase#MID} */
    public boolean isMidGame() {
        return currentPhase == GamePhase.MID;
    }

    /** @return {@code true} if the phase is {@link GamePhase#LATE} */
    public boolean isLateGame() {
        return currentPhase == GamePhase.LATE;
    }

    /**
     * Returns whether the arena is currently in INGAME state.
     *
     * <p>Used by subsystems that need to know if the game is actually running
     * (as opposed to waiting/countdown/ended).</p>
     *
     * @return {@code true} if the arena state is {@link SkyWarsState#INGAME}
     */
    public boolean isIngame() {
        return bot.getArena().getState() == SkyWarsState.INGAME;
    }

    /**
     * Returns whether the game is in a grace period (no PvP).
     *
     * <p>In LostSkyWars there is no explicit "grace period" state in
     * {@link SkyWarsState}. We approximate it as the first 30 seconds of INGAME
     * time, which matches typical SkyWars server configurations. Phase 6 will
     * refine this via the proper SkyWarsGameHook integration.</p>
     *
     * @return {@code true} if within the estimated grace period
     */
    public boolean isGracePeriod() {
        if (!ingameEverStarted || ingameStartedTimeMs <= 0L) return false;
        double elapsed = (System.currentTimeMillis() - ingameStartedTimeMs) / 1000.0;
        return elapsed < 30.0; // 30-second grace window (configurable in Phase 6)
    }

    /**
     * Returns the elapsed game time in seconds since the arena entered INGAME.
     *
     * @return seconds since game start, or 0 if game hasn't started
     */
    public double getGameTimeSeconds() {
        if (!ingameEverStarted || ingameStartedTimeMs <= 0L) return 0.0;
        return (System.currentTimeMillis() - ingameStartedTimeMs) / 1000.0;
    }

    /**
     * Returns a "time pressure" value in [0.0, 1.0].
     *
     * <p>Rises slowly in early game, accelerates in late game (quadratic curve).
     * Feeds directly into {@code TimePressureConsideration}.</p>
     *
     * @return time pressure [0.0, 1.0]
     */
    public double getTimePressure() {
        return MathUtil.polynomialCurve(gameProgress, 2.0);
    }

    /**
     * Returns a combat-urgency factor in [0.0, 1.0].
     *
     * <p>Low in early game (loot first), ramps to 1.0 in late game.
     * Used by the decision engine to bias toward FIGHT/HUNT actions.</p>
     *
     * @return combat urgency [0.0, 1.0]
     */
    public double getCombatUrgency() {
        switch (currentPhase) {
            case EARLY:
                // Low but not zero — enemy rushing forces combat
                return MathUtil.clamp(gameProgress * 1.5, 0.0, 0.3);
            case MID:
                return MathUtil.lerp(0.3, 0.7, (gameProgress - 0.33) / 0.34);
            case LATE:
            default:
                return MathUtil.lerp(0.7, 1.0, MathUtil.clamp((gameProgress - 0.67) / 0.33, 0.0, 1.0));
        }
    }

    /**
     * Returns the looting urgency in [0.0, 1.0].
     * Inverse of combat urgency — high when it is early game, low when late.
     *
     * @return looting urgency [0.0, 1.0]
     */
    public double getLootingUrgency() {
        return MathUtil.clamp(1.0 - getCombatUrgency(), 0.0, 1.0);
    }

    /**
     * Resets this tracker to its initial state (e.g., when a new game starts
     * or the bot is recycled for a different arena).
     */
    public void reset() {
        currentPhase           = GamePhase.EARLY;
        gameProgress           = 0.0;
        ingameEverStarted      = false;
        ingameStartedTimeMs    = 0L;
        cachedInitialPlayerCount = -1;
    }

    // ─── Inner enum ──────────────────────────────────────────────────────────────

    /**
     * Strategic game phases from the bot's decision-making perspective.
     *
     * <p>Distinct from {@link SkyWarsState}: this is about <em>strategy</em>
     * (should I loot or fight?), not game mechanics (is PvP allowed?).</p>
     */
    public enum GamePhase {
        /**
         * First ~60 seconds, or while &gt;70 % of players are alive.
         * Primary focus: looting, equipping, initial positioning.
         */
        EARLY,
        /**
         * 60–180 seconds, or 35–70 % of players alive.
         * Transition: finish looting, start hunting. Territory skirmishes.
         */
        MID,
        /**
         * 180+ seconds, &lt;35 % of players alive, or last 2 alive.
         * Full combat mode — hunt remaining enemies aggressively.
         */
        LATE
    }
}

