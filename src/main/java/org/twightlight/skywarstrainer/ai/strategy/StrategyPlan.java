package org.twightlight.skywarstrainer.ai.strategy;

import org.twightlight.skywarstrainer.ai.decision.DecisionContext;
import org.twightlight.skywarstrainer.ai.decision.DecisionEngine.BotAction;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * Represents a multi-phase strategic game plan for a bot.
 *
 * <p>A StrategyPlan contains an ordered list of {@link StrategicPhase} objects,
 * each with action multipliers, completion conditions, and abort conditions.
 * The plan tracks which phase is currently active and maintains a confidence
 * score that decays over time.</p>
 *
 * <p>The plan does NOT override the DecisionEngine — it applies score multipliers
 * that bias the utility evaluation toward actions aligned with the current phase's
 * strategic goals. The DecisionEngine always has the final say.</p>
 */
public class StrategyPlan {

    /** The ordered list of phases in this plan. */
    private final List<StrategicPhase> phases;

    /** The index of the currently active phase. */
    private int currentPhaseIndex;

    /**
     * Confidence score [0.0, 1.0] indicating how relevant this plan still is.
     * Starts at 1.0 and decays over time. When it drops below a threshold,
     * the StrategyPlanner generates a new plan.
     */
    private double confidence;

    /** The tick at which this plan was created. */
    private final long createdAtTick;

    /** Human-readable description of the overall plan for debug output. */
    private final String planDescription;

    /**
     * Cached active multipliers from the current phase. Updated when the
     * phase changes or when the plan is first created.
     */
    private Map<BotAction, Double> activeMultipliers;

    /**
     * Creates a new StrategyPlan.
     *
     * @param phases          the ordered list of phases
     * @param createdAtTick   the tick when this plan was created
     * @param planDescription a human-readable summary of the plan
     */
    public StrategyPlan(@Nonnull List<StrategicPhase> phases,
                        long createdAtTick,
                        @Nonnull String planDescription) {
        this.phases = new ArrayList<>(phases);
        this.currentPhaseIndex = 0;
        this.confidence = 1.0;
        this.createdAtTick = createdAtTick;
        this.planDescription = planDescription;
        this.activeMultipliers = new EnumMap<>(BotAction.class);
        updateActiveMultipliers();
    }

    // ─── Tick / Update ──────────────────────────────────────────

    /**
     * Updates the plan state: decays confidence, checks current phase
     * completion/abort conditions, and advances phases as needed.
     *
     * @param context            the current decision context
     * @param confidenceDecayRate per-tick confidence decay rate
     * @return true if the plan is still valid; false if it should be discarded
     */
    public boolean update(@Nonnull DecisionContext context, double confidenceDecayRate) {
        // Decay confidence
        confidence -= confidenceDecayRate;
        if (confidence <= 0.0) {
            confidence = 0.0;
            return false;
        }

        // Check if all phases are exhausted
        if (currentPhaseIndex >= phases.size()) {
            return false;
        }

        StrategicPhase currentPhase = phases.get(currentPhaseIndex);
        currentPhase.incrementTicks();

        // Check abort condition
        if (currentPhase.shouldAbort(context)) {
            // Phase aborted — reduce confidence significantly
            confidence *= 0.5;
            return false; // Signal re-planning
        }

        // Check completion condition
        if (currentPhase.isComplete(context)) {
            currentPhaseIndex++;
            if (currentPhaseIndex < phases.size()) {
                phases.get(currentPhaseIndex).resetTicks();
                updateActiveMultipliers();
            } else {
                // Plan completed successfully
                return false;
            }
        }

        return true;
    }

    /**
     * Updates the cached active multipliers from the current phase.
     */
    private void updateActiveMultipliers() {
        activeMultipliers.clear();
        if (currentPhaseIndex < phases.size()) {
            activeMultipliers.putAll(phases.get(currentPhaseIndex).getActionMultipliers());
        }
    }

    // ─── Getters ────────────────────────────────────────────────

    /** @return unmodifiable list of all phases in this plan */
    @Nonnull
    public List<StrategicPhase> getPhases() {
        return Collections.unmodifiableList(phases);
    }

    /** @return the index of the currently active phase */
    public int getCurrentPhaseIndex() {
        return currentPhaseIndex;
    }

    /** @return the currently active phase, or null if all phases are completed */
    @Nullable
    public StrategicPhase getCurrentPhase() {
        if (currentPhaseIndex < phases.size()) {
            return phases.get(currentPhaseIndex);
        }
        return null;
    }

    /** @return the plan's confidence score [0.0, 1.0] */
    public double getConfidence() {
        return confidence;
    }

    /**
     * Sets the confidence directly (e.g., after an LLM update or significant event).
     *
     * @param confidence the new confidence value, clamped to [0.0, 1.0]
     */
    public void setConfidence(double confidence) {
        this.confidence = Math.max(0.0, Math.min(1.0, confidence));
    }

    /** @return the tick at which this plan was created */
    public long getCreatedAtTick() {
        return createdAtTick;
    }

    /** @return the human-readable plan description */
    @Nonnull
    public String getPlanDescription() {
        return planDescription;
    }

    /**
     * Returns the action score multipliers for the current phase.
     * Actions not in the map should use a multiplier of 1.0.
     *
     * @return unmodifiable map of action multipliers
     */
    @Nonnull
    public Map<BotAction, Double> getActiveMultipliers() {
        return Collections.unmodifiableMap(activeMultipliers);
    }

    /**
     * Returns the number of phases in this plan.
     *
     * @return the phase count
     */
    public int getPhaseCount() {
        return phases.size();
    }

    /**
     * Returns whether all phases have been completed.
     *
     * @return true if all phases done
     */
    public boolean isCompleted() {
        return currentPhaseIndex >= phases.size();
    }

    @Override
    public String toString() {
        String currentPhaseName = (currentPhaseIndex < phases.size())
                ? phases.get(currentPhaseIndex).getName() : "DONE";
        return String.format("Plan{phases=%d, current=%d(%s), confidence=%.2f, desc='%s'}",
                phases.size(), currentPhaseIndex, currentPhaseName, confidence, planDescription);
    }
}
