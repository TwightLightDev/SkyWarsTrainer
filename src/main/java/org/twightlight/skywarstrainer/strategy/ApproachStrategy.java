package org.twightlight.skywarstrainer.strategy;

import org.bukkit.entity.LivingEntity;
import org.twightlight.skywarstrainer.bot.TrainerBot;

import javax.annotation.Nonnull;

/**
 * Interface for all approach strategies. An approach strategy defines HOW
 * the bot navigates from its current position to an enemy target on a
 * different island.
 *
 * <p>This is the STRATEGIC DECISION about route and method, distinct from
 * bridging (which is the physical block placement).</p>
 */
public interface ApproachStrategy {

    /**
     * Returns the unique name of this approach strategy.
     *
     * @return the strategy name
     */
    @Nonnull
    String getName();

    /**
     * Determines whether this approach strategy should be used in the current situation.
     *
     * @param bot     the bot
     * @param target  the target entity
     * @param context the current approach context
     * @return true if this strategy is viable
     */
    boolean shouldUse(@Nonnull TrainerBot bot, @Nonnull LivingEntity target,
                      @Nonnull ApproachContext context);

    /**
     * Calculates the approach path to the target.
     *
     * @param bot    the bot
     * @param target the target entity
     * @return the planned approach path
     */
    @Nonnull
    ApproachPath calculateApproachPath(@Nonnull TrainerBot bot, @Nonnull LivingEntity target);

    /**
     * Ticks one frame of the approach execution.
     *
     * @param bot the bot
     * @return the result of this tick
     */
    @Nonnull
    ApproachTickResult tick(@Nonnull TrainerBot bot);

    /**
     * Returns the priority score for this strategy. Higher = more likely to be chosen.
     *
     * @param bot the bot
     * @return the priority score
     */
    double getPriority(@Nonnull TrainerBot bot);

    /**
     * Resets the strategy's internal state for a new approach.
     */
    void reset();
}
