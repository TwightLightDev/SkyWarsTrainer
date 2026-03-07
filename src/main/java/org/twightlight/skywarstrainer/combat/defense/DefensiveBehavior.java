package org.twightlight.skywarstrainer.combat.defense;

import org.twightlight.skywarstrainer.bot.TrainerBot;

import javax.annotation.Nonnull;

/**
 * Interface for individual defensive behavior implementations. Each behavior
 * corresponds to one {@link DefensiveAction} type and handles the multi-tick
 * execution of that defense.
 *
 * <p>Defensive behaviors follow a lifecycle: evaluate (shouldActivate) → reset
 * → tick until complete → reset. Only one defensive behavior is active at a
 * time, managed by {@link DefensiveActionEngine}.</p>
 */
public interface DefensiveBehavior {

    /**
     * Returns the human-readable name of this defensive behavior.
     *
     * @return the name
     */
    @Nonnull
    String getName();

    /**
     * Returns the {@link DefensiveAction} enum type this behavior implements.
     *
     * @return the action type
     */
    @Nonnull
    DefensiveAction getActionType();

    /**
     * Evaluates whether this defensive behavior should activate in the current
     * situation. Considers difficulty parameters, personality, threat state,
     * and available resources.
     *
     * @param bot the bot
     * @return true if this behavior should activate
     */
    boolean shouldActivate(@Nonnull TrainerBot bot);

    /**
     * Returns the priority of this behavior for the current situation. Higher
     * values take precedence when multiple behaviors could activate.
     *
     * @param bot the bot
     * @return the priority score (0.0 = minimal, higher = more urgent)
     */
    double getPriority(@Nonnull TrainerBot bot);

    /**
     * Ticks one frame of this defensive behavior.
     *
     * @param bot the bot
     */
    void tick(@Nonnull TrainerBot bot);

    /**
     * Returns whether this behavior has completed its action.
     *
     * @return true if complete
     */
    boolean isComplete();

    /**
     * Resets all internal state. Called at activation start and when the
     * behavior completes or is cancelled.
     */
    void reset();
}
