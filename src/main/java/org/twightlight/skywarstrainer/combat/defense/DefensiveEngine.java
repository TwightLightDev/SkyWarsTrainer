package org.twightlight.skywarstrainer.combat.defense;

import org.twightlight.skywarstrainer.bot.TrainerBot;
import org.twightlight.skywarstrainer.util.DebugLogger;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;

/**
 * Central manager for defensive behaviors. Evaluates defensive actions each
 * tick and executes the highest-priority one when applicable.
 *
 * <p>Ticked during CAMPING and FIGHTING states. Can also be triggered as
 * interrupts during other states (e.g., when an enemy starts bridging toward
 * the bot during LOOTING).</p>
 *
 * <p>The manager maintains a list of {@link DefensiveBehavior} implementations
 * and scores them based on the current situation, difficulty parameters, and
 * personality multipliers.</p>
 */
public class DefensiveEngine {

    private final TrainerBot bot;

    /** All registered defensive behaviors. */
    private final List<DefensiveBehavior> behaviors;

    /** The currently executing defensive behavior, or null. */
    private DefensiveBehavior activeBehavior;

    /** Cooldown: minimum ticks between defensive evaluations to avoid thrashing. */
    private int evaluationCooldown;
    private static final int EVAL_COOLDOWN_TICKS = 20;

    /**
     * Creates a new DefensiveActionManager for the given bot.
     *
     * @param bot the owning trainer bot
     */
    public DefensiveEngine(@Nonnull TrainerBot bot) {
        this.bot = bot;
        this.behaviors = new ArrayList<>();
        this.activeBehavior = null;
        this.evaluationCooldown = 0;

        registerBehaviors();
    }

    /**
     * Registers all defensive behavior implementations.
     */
    private void registerBehaviors() {
        behaviors.add(new BridgeCutter());
        behaviors.add(new ProjectileZoner());
        behaviors.add(new RetreatHealer());
        behaviors.add(new BlockBarricade());
        behaviors.add(new BridgeTrap());
    }

    /**
     * Main tick method. Ticks the active behavior or evaluates for a new one.
     *
     * @param bot the bot (passed explicitly for clarity in behavior tree callbacks)
     */
    public void tick(@Nonnull TrainerBot bot) {
        // Tick cooldown
        if (evaluationCooldown > 0) {
            evaluationCooldown--;
        }

        // If a behavior is active, tick it
        if (activeBehavior != null) {
            if (activeBehavior.isComplete()) {
                DebugLogger.log(bot, "Defense: %s completed", activeBehavior.getName());
                activeBehavior.reset();
                activeBehavior = null;
                evaluationCooldown = EVAL_COOLDOWN_TICKS;
            } else {
                activeBehavior.tick(bot);
                return; // Active behavior consumes the tick
            }
        }

        // Evaluate for a new defensive action if cooldown has elapsed
        if (evaluationCooldown <= 0) {
            DefensiveBehavior best = checkDefensiveActions(bot);
            if (best != null) {
                activeBehavior = best;
                best.reset();
                DebugLogger.log(bot, "Defense: activating %s", best.getName());
            }
        }
    }

    /**
     * Evaluates all defensive behaviors and returns the highest priority
     * applicable one, or null if none should activate.
     *
     * @param bot the bot
     * @return the best defensive behavior to activate, or null
     */
    @Nullable
    public DefensiveBehavior checkDefensiveActions(@Nonnull TrainerBot bot) {
        DefensiveBehavior best = null;
        double bestPriority = 0.0;

        for (DefensiveBehavior behavior : behaviors) {
            if (!behavior.shouldActivate(bot)) continue;

            double priority = behavior.getPriority(bot);
            if (priority > bestPriority) {
                bestPriority = priority;
                best = behavior;
            }
        }

        return best;
    }

    /**
     * Forces an immediate evaluation and activation, bypassing cooldown.
     * Used for interrupt-based triggers (e.g., enemy starts bridging).
     *
     * @param bot the bot
     */
    public void forceEvaluate(@Nonnull TrainerBot bot) {
        evaluationCooldown = 0;
        DefensiveBehavior best = checkDefensiveActions(bot);
        if (best != null) {
            if (activeBehavior != null) {
                activeBehavior.reset();
            }
            activeBehavior = best;
            best.reset();
            DebugLogger.log(bot, "Defense: force-activated %s", best.getName());
        }
    }

    /**
     * Cancels the currently active defensive behavior.
     */
    public void cancel() {
        if (activeBehavior != null) {
            activeBehavior.reset();
            activeBehavior = null;
        }
    }

    /**
     * Returns whether a defensive behavior is currently executing.
     *
     * @return true if a behavior is active
     */
    public boolean isActive() {
        return activeBehavior != null && !activeBehavior.isComplete();
    }

    /**
     * Returns the currently active defensive behavior.
     *
     * @return the active behavior, or null
     */
    @Nullable
    public DefensiveBehavior getActiveBehavior() {
        return activeBehavior;
    }

    /**
     * Returns the action type of the active behavior, or null.
     *
     * @return the active defensive action type
     */
    @Nullable
    public DefensiveAction getActiveActionType() {
        return activeBehavior != null ? activeBehavior.getActionType() : null;
    }
}
