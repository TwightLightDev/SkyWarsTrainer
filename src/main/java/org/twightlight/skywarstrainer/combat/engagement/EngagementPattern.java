package org.twightlight.skywarstrainer.combat.engagement;

import org.bukkit.entity.LivingEntity;
import org.twightlight.skywarstrainer.bot.TrainerBot;

import javax.annotation.Nonnull;

/**
 * Interface for high-level engagement patterns that override the normal
 * per-tick combat strategy selection for a sequence of ticks.
 *
 * <p>Engagement patterns represent pro-level combat sequences like edge-knock
 * plays, combo locks, KB cancellation, and third-partying. When active, a
 * pattern takes control of combat behavior until it completes.</p>
 */
public interface EngagementPattern {

    /** @return the unique name of this pattern */
    @Nonnull
    String getName();

    /**
     * Checks whether this pattern should activate given the current situation.
     *
     * @param bot     the bot
     * @param target  the current combat target
     * @param context engagement context (combo state, positioning, etc.)
     * @return true if this pattern should activate
     */
    boolean shouldActivate(@Nonnull TrainerBot bot, @Nonnull LivingEntity target,
                           @Nonnull EngagementContext context);

    /**
     * Ticks one frame of the pattern's execution.
     *
     * @param bot    the bot
     * @param target the current combat target
     */
    void tick(@Nonnull TrainerBot bot, @Nonnull LivingEntity target);

    /**
     * Returns the priority of this pattern. Higher = evaluated first.
     *
     * @param bot the bot
     * @return the priority score
     */
    double getPriority(@Nonnull TrainerBot bot);

    /** @return true if the pattern has completed its sequence */
    boolean isComplete();

    /** Resets the pattern for a new activation. */
    void reset();
}
