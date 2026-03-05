package org.twightlight.skywarstrainer.combat.strategies;

import org.twightlight.skywarstrainer.bot.TrainerBot;
import org.twightlight.skywarstrainer.config.DifficultyConfig.DifficultyProfile;

import javax.annotation.Nonnull;

/**
 * Interface for all PvP combat strategies.
 *
 * <p>Each strategy represents a specific combat technique (strafing, W-tapping,
 * block-hitting, rod combos, etc.). The CombatEngine evaluates which strategies
 * are applicable given the current context, scores them by priority, and executes
 * the highest-priority strategy each tick.</p>
 *
 * <p>Strategies are stateful — they maintain internal timers, phase tracking,
 * and cooldowns specific to their technique. They are created per-bot and persist
 * for the bot's lifetime.</p>
 */
public interface CombatStrategy {

    /**
     * Returns a unique name for this strategy, used in debug output and logging.
     *
     * @return the strategy name
     */
    @Nonnull
    String getName();

    /**
     * Checks whether this strategy can be activated in the current situation.
     * Considers: range to target, available items, difficulty prerequisites, cooldowns.
     *
     * @param bot the bot executing combat
     * @return true if this strategy can execute right now
     */
    boolean shouldActivate(@Nonnull TrainerBot bot);

    /**
     * Executes one tick of this strategy. Called when this strategy is the selected
     * active strategy for the current tick.
     *
     * @param bot the bot executing combat
     */
    void execute(@Nonnull TrainerBot bot);

    /**
     * Returns the priority of this strategy given the current context. Higher priority
     * strategies are preferred. Personality and difficulty modifiers should influence
     * this score.
     *
     * @param bot the bot evaluating strategies
     * @return the priority score (higher = more preferred); 0 or negative = don't use
     */
    double getPriority(@Nonnull TrainerBot bot);

    /**
     * Resets this strategy's internal state. Called when combat ends or target changes.
     */
    void reset();
}

