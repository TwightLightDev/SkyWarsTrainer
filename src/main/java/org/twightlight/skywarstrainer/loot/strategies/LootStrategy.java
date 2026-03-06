package org.twightlight.skywarstrainer.loot.strategies;

import org.bukkit.block.Chest;
import org.twightlight.skywarstrainer.bot.TrainerBot;

import javax.annotation.Nonnull;

/**
 * Interface for all looting strategies.
 *
 * <p>A LootStrategy defines how a bot interacts with a chest to acquire items.
 * Different strategies trade off speed, thoroughness, and enemy denial. The
 * {@link org.twightlight.skywarstrainer.loot.LootEngine} selects the appropriate
 * strategy based on the bot's personality and difficulty.</p>
 */
public interface LootStrategy {

    /**
     * Returns the human-readable name of this strategy.
     *
     * @return the strategy name
     */
    @Nonnull
    String getName();

    /**
     * Initializes the strategy for looting a specific chest.
     * Must be called before the first {@link #tick(TrainerBot)}.
     *
     * @param bot   the bot performing the loot
     * @param chest the chest to loot
     */
    void initialize(@Nonnull TrainerBot bot, @Nonnull Chest chest);

    /**
     * Performs one tick of the looting sequence.
     *
     * @param bot the bot performing the loot
     * @return the result of this tick
     */
    @Nonnull
    LootTickResult tick(@Nonnull TrainerBot bot);

    /**
     * Resets all internal state for reuse.
     */
    void reset();

    /**
     * Returns true if this strategy is finished (all items processed or chest empty).
     *
     * @return true if looting is complete
     */
    boolean isComplete();

    /**
     * Result of a single tick of loot execution.
     */
    enum LootTickResult {
        /** An item was taken this tick. */
        ITEM_TAKEN,
        /** The strategy is in progress (approaching, opening, selecting). */
        IN_PROGRESS,
        /** Looting is complete — chest fully processed. */
        COMPLETE,
        /** Looting failed (chest destroyed, inventory full, interrupted). */
        FAILED
    }
}
