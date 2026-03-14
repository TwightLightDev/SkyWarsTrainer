// ═══════════════════════════════════════════════════════════════════
// FILE: src/main/java/org/twightlight/skywarstrainer/ai/state/BotStateMachine.java
// PHASE 1 CHANGES: Add StateTransitionListener + notify on transition
// ═══════════════════════════════════════════════════════════════════

package org.twightlight.skywarstrainer.ai.state;

import org.twightlight.skywarstrainer.SkyWarsTrainer;
import org.twightlight.skywarstrainer.ai.engine.BehaviorTree;
import org.twightlight.skywarstrainer.ai.engine.NodeStatus;
import org.twightlight.skywarstrainer.bot.TrainerBot;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.logging.Level;


/**
 * Manages the bot's macro-behavioral state and drives per-state behavior trees.
 *
 * <p>The state machine sits between the Utility AI (which picks WHAT to do) and
 * the Behavior Trees (which execute HOW to do it). When the Utility AI selects
 * an action, it maps to a {@link BotState} and calls {@link #transitionTo(BotState, String)}.
 * The state machine handles enter/exit hooks and delegates per-tick execution to
 * the active state's behavior tree.</p>
 *
 * <h3>Transition log</h3>
 * <p>The last N transitions are kept in a ring buffer for debug output.
 * The {@link StateTransition} record is also used to fire custom Bukkit events
 * in Phase 6.</p>
 *
 * <h3>Integration with DecisionEngine</h3>
 * <p>The DecisionEngine calls {@link #transitionTo(BotState, String)} when the
 * highest-scoring utility action maps to a different state. The state machine
 * validates the transition (e.g., you can always force FIGHTING when taking damage)
 * and switches the active behavior tree accordingly.</p>
 */
public class BotStateMachine {

    /** Maximum number of recent transitions to keep for debug log. */
    private static final int TRANSITION_LOG_SIZE = 20;

    private final TrainerBot bot;

    /** The currently active state. Never null after initialization. */
    private BotState currentState;

    /** Behavior trees keyed by the state they serve. */
    private final Map<BotState, BehaviorTree> behaviorTrees;

    /** Ring buffer of recent transitions for debug/logging. */
    private final Deque<StateTransition> transitionLog;

    /** The tick at which the current state was entered. Used for time-in-state queries. */
    private long stateEnteredTick;

    // [FIX-A1/A4/D3/E1] Internal transition listeners for subsystem cleanup
    private final List<StateTransitionListener> transitionListeners;

    /**
     * Functional interface for internal transition listeners.
     * Subsystems register a listener to be notified synchronously
     * when the state machine transitions, allowing them to clean up
     * stale internal state (e.g., cancel active defensive behaviors,
     * disengage combat, abort approaches).
     */
    @FunctionalInterface
    public interface StateTransitionListener {
        /**
         * Called synchronously during transitionTo() AFTER the state has changed
         * but BEFORE the debug log is printed.
         *
         * @param oldState the state being left
         * @param newState the state being entered
         */
        void onTransition(@Nonnull BotState oldState, @Nonnull BotState newState);
    }

    /**
     * Creates a new BotStateMachine for the given bot.
     * The initial state is {@link BotState#IDLE}.
     *
     * @param bot the owning trainer bot
     */
    public BotStateMachine(@Nonnull TrainerBot bot) {
        this.bot = bot;
        this.currentState = BotState.IDLE;
        this.behaviorTrees = new EnumMap<>(BotState.class);
        this.transitionLog = new ArrayDeque<>();
        this.stateEnteredTick = bot.getLocalTickCount();
        this.transitionListeners = new ArrayList<>(); // [FIX-A1/A4/D3/E1]
    }

    // ─── Transition Listener Registration ───────────────────────

    /**
     * Registers an internal transition listener. Called during subsystem init.
     * Listeners are notified synchronously during transitionTo().
     *
     * @param listener the listener to add
     */
    // [FIX-A1/A4/D3/E1] New method for subsystem cleanup on state transitions
    public void addTransitionListener(@Nonnull StateTransitionListener listener) {
        transitionListeners.add(listener);
    }

    // ─── Behavior Tree Registration ─────────────────────────────

    /**
     * Registers a behavior tree for a given state. Each state should have
     * exactly one tree registered before the bot starts ticking.
     *
     * @param state the state this tree governs
     * @param tree  the behavior tree for micro-actions within this state
     */
    public void registerTree(@Nonnull BotState state, @Nonnull BehaviorTree tree) {
        behaviorTrees.put(state, tree);
    }

    /**
     * Returns the behavior tree for a given state.
     *
     * @param state the state to look up
     * @return the tree, or null if none registered
     */
    @Nullable
    public BehaviorTree getTree(@Nonnull BotState state) {
        return behaviorTrees.get(state);
    }

    // ─── State Transitions ──────────────────────────────────────

    /**
     * Transitions to a new state with a human-readable reason.
     *
     * <p>Process:
     * <ol>
     *   <li>If the new state equals the current state, do nothing (no-op).</li>
     *   <li>Reset the current state's behavior tree (so it starts fresh if re-entered).</li>
     *   <li>Log the transition.</li>
     *   <li>Set the new state as current and record entry tick.</li>
     *   <li>[FIX-A1/A4/D3/E1] Notify all transition listeners for subsystem cleanup.</li>
     * </ol></p>
     *
     * @param newState the state to transition to
     * @param reason   a human-readable reason (for debug logs and events)
     * @return true if the transition occurred, false if already in that state
     */
    public boolean transitionTo(@Nonnull BotState newState, @Nonnull String reason) {
        if (newState == currentState) {
            return false;
        }

        BotState oldState = currentState;

        // Reset the old state's behavior tree so it starts clean on re-entry
        BehaviorTree oldTree = behaviorTrees.get(oldState);
        if (oldTree != null) {
            try {
                oldTree.reset(bot);
            } catch (Exception e) {
                SkyWarsTrainer.getInstance().getLogger().log(Level.WARNING,
                        "Error resetting BT for state " + oldState.name(), e);
            }
        }

        // Create and log the transition
        StateTransition transition = new StateTransition(
                oldState, newState, reason, bot.getLocalTickCount());
        logTransition(transition);

        // Switch state
        currentState = newState;
        stateEnteredTick = bot.getLocalTickCount();

        // [FIX-A1/A4/D3/E1] Notify all internal transition listeners for subsystem cleanup
        for (StateTransitionListener listener : transitionListeners) {
            try {
                listener.onTransition(oldState, newState);
            } catch (Exception e) {
                SkyWarsTrainer.getInstance().getLogger().log(Level.WARNING,
                        "Error in transition listener for " + oldState + " -> " + newState, e);
            }
        }

        // Fire BotStateChangeEvent (external API event)
        try {
            org.twightlight.skywarstrainer.api.events.BotStateChangeEvent event =
                    new org.twightlight.skywarstrainer.api.events.BotStateChangeEvent(bot, oldState, newState);
            org.bukkit.Bukkit.getPluginManager().callEvent(event);
        } catch (Exception e) {
            // Don't let event errors block state transitions
        }

        if (bot.getProfile().isDebugMode()) {
            SkyWarsTrainer.getInstance().getLogger().info(
                    "[DEBUG] " + bot.getName() + " " + transition);
        }

        return true;
    }

    /**
     * Forces a transition to a state even if already in it. This resets the
     * behavior tree for the current state. Useful for "restart my current behavior"
     * scenarios (e.g., target changed while fighting — restart fight BT).
     *
     * @param state  the state to force-enter
     * @param reason the reason for the forced transition
     */
    public void forceTransition(@Nonnull BotState state, @Nonnull String reason) {
        BotState old = currentState;
        BehaviorTree oldTree = behaviorTrees.get(old);
        if (oldTree != null) {
            oldTree.reset(bot);
        }

        StateTransition transition = new StateTransition(
                old, state, "FORCED: " + reason, bot.getLocalTickCount());
        logTransition(transition);

        currentState = state;
        stateEnteredTick = bot.getLocalTickCount();

        // [FIX-A1/A4/D3/E1] Notify listeners even on forced transitions
        // (old != state is not guaranteed, but cleanup is still needed for force-restart)
        for (StateTransitionListener listener : transitionListeners) {
            try {
                listener.onTransition(old, state);
            } catch (Exception e) {
                SkyWarsTrainer.getInstance().getLogger().log(Level.WARNING,
                        "Error in transition listener for forced " + old + " -> " + state, e);
            }
        }
    }

    // ─── Tick ───────────────────────────────────────────────────

    /**
     * Ticks the current state's behavior tree once.
     *
     * <p>Returns the tree's status so the caller (TrainerBot tick loop) can
     * react to it. If the tree returns SUCCESS or FAILURE, the state machine
     * does NOT automatically transition — that is the DecisionEngine's job.
     * The tree will auto-reset on the next tick call (BehaviorTree handles this).</p>
     *
     * @return the behavior tree's status for this tick, or FAILURE if no tree registered
     */
    @Nonnull
    public NodeStatus tick() {
        BehaviorTree tree = behaviorTrees.get(currentState);
        if (tree == null) {
            // No tree for this state — treat as success (state does nothing)
            return NodeStatus.SUCCESS;
        }

        try {
            return tree.tick(bot);
        } catch (Exception e) {
            SkyWarsTrainer.getInstance().getLogger().log(Level.WARNING,
                    "Error ticking BT for state " + currentState.name() + " on bot " + bot.getName(), e);
            return NodeStatus.FAILURE;
        }
    }

    // ─── Queries ────────────────────────────────────────────────

    /**
     * Returns the current state.
     *
     * @return the active state
     */
    @Nonnull
    public BotState getCurrentState() {
        return currentState;
    }

    /**
     * Returns how many ticks the bot has been in the current state.
     *
     * @return ticks since last state transition
     */
    public long getTicksInCurrentState() {
        return bot.getLocalTickCount() - stateEnteredTick;
    }

    /**
     * Returns the tick at which the current state was entered.
     *
     * @return the entry tick
     */
    public long getStateEnteredTick() {
        return stateEnteredTick;
    }

    /**
     * Returns the most recent state transition, or null if none have occurred.
     *
     * @return the last transition
     */
    @Nullable
    public StateTransition getLastTransition() {
        return transitionLog.peekLast();
    }

    /**
     * Returns the recent transition log (up to {@value TRANSITION_LOG_SIZE} entries).
     *
     * @return an iterable of recent transitions, oldest first
     */
    @Nonnull
    public Iterable<StateTransition> getTransitionLog() {
        return transitionLog;
    }

    /**
     * Returns true if the bot is in a combat-related state.
     *
     * @return true if FIGHTING, FLEEING, or HUNTING
     */
    public boolean isInCombatState() {
        return currentState == BotState.FIGHTING
                || currentState == BotState.FLEEING
                || currentState == BotState.HUNTING;
    }

    /**
     * Returns true if the bot is in a movement-focused state.
     *
     * @return true if BRIDGING, HUNTING, or FLEEING
     */
    public boolean isInMovementState() {
        return currentState == BotState.BRIDGING
                || currentState == BotState.HUNTING
                || currentState == BotState.FLEEING;
    }

    // ─── Transition Log ─────────────────────────────────────────

    /**
     * Adds a transition to the ring buffer log. Evicts oldest entry if full.
     */
    private void logTransition(@Nonnull StateTransition transition) {
        if (transitionLog.size() >= TRANSITION_LOG_SIZE) {
            transitionLog.pollFirst();
        }
        transitionLog.addLast(transition);
    }

    @Override
    public String toString() {
        return "BotStateMachine{state=" + currentState.name()
                + ", ticksInState=" + getTicksInCurrentState() + "}";
    }
}
