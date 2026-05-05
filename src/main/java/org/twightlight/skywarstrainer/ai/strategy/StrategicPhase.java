package org.twightlight.skywarstrainer.ai.strategy;

import org.twightlight.skywarstrainer.ai.decision.DecisionContext;
import org.twightlight.skywarstrainer.ai.decision.DecisionEngine.BotAction;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.Collections;
import java.util.EnumMap;
import java.util.Map;

/**
 * Represents a single phase within a {@link StrategyPlan}.
 *
 * <p>Each phase has a human-readable name, a strategic goal description,
 * action score multipliers that bias the DecisionEngine, and conditions
 * for completion and abort. Phases are executed sequentially within a plan.</p>
 *
 * <p>Example phase: "Loot Own Island" with multipliers {LOOT_OWN_ISLAND: 2.5, FIGHT_NEAREST: 0.3}
 * and completion condition "equipmentScore > 0.4 OR unlootedChestCount == 0".</p>
 */
public class StrategicPhase {

    /** Human-readable name of this phase (e.g., "Loot Own Island"). */
    private final String name;

    /** Descriptive goal for debug/LLM output (e.g., "Gather initial equipment from own island chests"). */
    private final String goal;

    /**
     * Score multipliers applied to DecisionEngine actions during this phase.
     * A multiplier of 2.0 doubles the action's utility score; 0.5 halves it.
     * Actions not in this map receive a multiplier of 1.0 (no change).
     */
    private final Map<BotAction, Double> actionMultipliers;

    /**
     * Condition that, when true, indicates this phase is complete and the plan
     * should advance to the next phase. May be null if the phase relies only
     * on the max duration timer.
     */
    private final PhaseCondition completionCondition;

    /**
     * Condition that, when true, causes this phase to be aborted (skipped).
     * Used for fallback logic: e.g., "health < 0.3 → abort current phase and flee."
     * May be null if no abort conditions are defined.
     */
    private final PhaseCondition abortCondition;

    /** Maximum ticks this phase can last before being forcibly completed. */
    private final int maxDurationTicks;

    /** Tick count tracking how long this phase has been active. */
    private long ticksActive;

    /**
     * Creates a new StrategicPhase.
     *
     * @param name                the phase name
     * @param goal                the phase goal description
     * @param actionMultipliers   score multipliers for DecisionEngine actions
     * @param completionCondition condition to advance to next phase (nullable)
     * @param abortCondition      condition to abort this phase (nullable)
     * @param maxDurationTicks    max ticks before forced completion
     */
    public StrategicPhase(@Nonnull String name,
                          @Nonnull String goal,
                          @Nonnull Map<BotAction, Double> actionMultipliers,
                          @Nullable PhaseCondition completionCondition,
                          @Nullable PhaseCondition abortCondition,
                          int maxDurationTicks) {
        this.name = name;
        this.goal = goal;
        this.actionMultipliers = new EnumMap<>(actionMultipliers);
        this.completionCondition = completionCondition;
        this.abortCondition = abortCondition;
        this.maxDurationTicks = maxDurationTicks;
        this.ticksActive = 0;
    }

    // ─── Evaluation ─────────────────────────────────────────────

    /**
     * Checks whether this phase's completion condition is met.
     *
     * @param context the current decision context
     * @return true if the phase is complete
     */
    public boolean isComplete(@Nonnull DecisionContext context) {
        if (ticksActive >= maxDurationTicks) {
            return true;
        }
        if (completionCondition != null) {
            return completionCondition.test(context);
        }
        return false;
    }

    /**
     * Checks whether this phase's abort condition is triggered.
     *
     * @param context the current decision context
     * @return true if the phase should be aborted
     */
    public boolean shouldAbort(@Nonnull DecisionContext context) {
        if (abortCondition != null) {
            return abortCondition.test(context);
        }
        return false;
    }

    /**
     * Increments the active tick counter.
     */
    public void incrementTicks() {
        ticksActive++;
    }

    /**
     * Resets the active tick counter. Called when the phase becomes active.
     */
    public void resetTicks() {
        ticksActive = 0;
    }

    // ─── Getters ────────────────────────────────────────────────

    /** @return the phase name */
    @Nonnull
    public String getName() {
        return name;
    }

    /** @return the phase goal description */
    @Nonnull
    public String getGoal() {
        return goal;
    }

    /** @return unmodifiable map of action score multipliers */
    @Nonnull
    public Map<BotAction, Double> getActionMultipliers() {
        return Collections.unmodifiableMap(actionMultipliers);
    }

    /** @return ticks this phase has been active */
    public long getTicksActive() {
        return ticksActive;
    }

    /** @return maximum duration in ticks */
    public int getMaxDurationTicks() {
        return maxDurationTicks;
    }

    @Override
    public String toString() {
        return "Phase{" + name + ", goal='" + goal + "', multipliers=" + actionMultipliers.size()
                + ", ticks=" + ticksActive + "/" + maxDurationTicks + "}";
    }

    // ─── Functional Interface ───────────────────────────────────

    /**
     * Functional interface for phase completion and abort conditions.
     * Evaluates a {@link DecisionContext} and returns true if the condition is met.
     */
    @FunctionalInterface
    public interface PhaseCondition {

        /**
         * Tests whether this condition is satisfied given the current game state.
         *
         * @param context the decision context snapshot
         * @return true if the condition is met
         */
        boolean test(@Nonnull DecisionContext context);
    }
}
