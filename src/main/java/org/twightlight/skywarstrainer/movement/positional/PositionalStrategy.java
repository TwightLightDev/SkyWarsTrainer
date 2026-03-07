package org.twightlight.skywarstrainer.movement.positional;

import org.bukkit.Location;
import org.twightlight.skywarstrainer.bot.TrainerBot;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.Map;

/**
 * Interface for macro-level positional strategies. These control WHERE the
 * bot positions itself on the map — not individual movements, but strategic
 * island/area control decisions.
 */
public interface PositionalStrategy {

    /** @return the unique name of this strategy */
    @Nonnull
    String getName();

    /**
     * Checks whether this strategy should activate in the current context.
     *
     * @param bot the bot
     * @return true if this strategy should activate
     */
    boolean shouldActivate(@Nonnull TrainerBot bot);

    /**
     * Returns the target position this strategy wants the bot to move toward.
     *
     * @param bot the bot
     * @return the target position, or null if the bot should hold position
     */
    @Nullable
    Location getTargetPosition(@Nonnull TrainerBot bot);

    /**
     * Returns utility score modifiers to apply while this strategy is active.
     * Keys are BotAction names or generic keys; values are multipliers.
     *
     * @return map of action name to score multiplier
     */
    @Nonnull
    Map<String, Double> getUtilityBonus();

    /**
     * Ticks this strategy once per evaluation cycle.
     *
     * @param bot the bot
     */
    void tick(@Nonnull TrainerBot bot);

    /**
     * Returns whether this strategy has completed.
     *
     * @return true if the strategy is done
     */
    boolean isComplete();

    /**
     * Resets internal state.
     */
    void reset();
}
