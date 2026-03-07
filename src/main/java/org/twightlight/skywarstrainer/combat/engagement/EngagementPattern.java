package org.twightlight.skywarstrainer.combat.engagement;

import org.bukkit.entity.LivingEntity;
import org.twightlight.skywarstrainer.bot.TrainerBot;

import javax.annotation.Nonnull;

/**
 * Interface for advanced engagement patterns. An engagement pattern is a
 * multi-tick combat sequence that overrides normal per-tick strategy selection
 * (e.g., a full edge-knock sequence, or a combo-lock chain).
 *
 * <p>While a pattern is active, it takes full control of combat actions.
 * When the pattern completes (or fails), control returns to normal
 * CombatEngine strategy selection.</p>
 *
 * <p><b>UPDATED (Phase 7):</b> {@code shouldActivate} now accepts an
 * {@link EngagementContext} parameter so patterns can inspect combo state,
 * void proximity, health fractions, etc. without re-querying every subsystem.</p>
 */
public interface EngagementPattern {

    /** @return the unique name of this pattern */
    @Nonnull
    String getName();

    /**
     * Checks whether this pattern should activate in the current situation.
     * Receives a pre-populated {@link EngagementContext} containing combo state,
     * void proximity, health fractions, and enemy count information.
     *
     * @param bot     the bot
     * @param target  the current combat target
     * @param context the pre-populated engagement context snapshot
     * @return true if this pattern should activate
     */
    boolean shouldActivate(@Nonnull TrainerBot bot, @Nonnull LivingEntity target,
                           @Nonnull EngagementContext context);

    /**
     * Ticks one frame of this engagement pattern. Called every tick while active.
     *
     * @param bot    the bot
     * @param target the combat target
     */
    void tick(@Nonnull TrainerBot bot, @Nonnull LivingEntity target);

    /**
     * Returns the priority of this pattern. Higher priority patterns are
     * preferred when multiple could activate.
     *
     * @param bot the bot
     * @return the priority score
     */
    double getPriority(@Nonnull TrainerBot bot);

    /**
     * Returns whether this pattern has completed its sequence.
     *
     * @return true if the pattern is done
     */
    boolean isComplete();

    /**
     * Resets internal state. Called when the pattern ends or combat ends.
     */
    void reset();
}
