// ═══════════════════════════════════════════════════════════════════
// File: src/main/java/org/twightlight/skywarstrainer/loot/strategies/LootStrategy.java
// ═══════════════════════════════════════════════════════════════════
package org.twightlight.skywarstrainer.loot.strategies;

import org.bukkit.Location;
import org.twightlight.skywarstrainer.bot.TrainerBot;

import javax.annotation.Nonnull;

/**
 * Interface for all chest looting strategies.
 *
 * <p>Each strategy defines a specific approach to looting chests:
 * how the bot opens/breaks the chest, which items to take, and how
 * fast the looting proceeds. The LootEngine selects the appropriate
 * strategy based on personality and difficulty.</p>
 *
 * <p>Strategies are stateful and maintain per-loot-action state (current
 * chest, items taken, tick counter). They are reset between chest interactions.</p>
 */
public interface LootStrategy {

    /**
     * Returns the unique name of this loot strategy.
     *
     * @return the strategy name
     */
    @Nonnull
    String getName();

    /**
     * Initializes this strategy for looting a specific chest.
     *
     * @param bot           the bot performing the loot
     * @param chestLocation the location of the chest to loot
     */
    void initialize(@Nonnull TrainerBot bot, @Nonnull Location chestLocation);

    /**
     * Ticks one frame of the looting process. Called each tick while the
     * bot is actively looting a chest.
     *
     * @param bot the bot
     * @return the result of this tick
     */
    @Nonnull
    LootTickResult tick(@Nonnull TrainerBot bot);

    /**
     * Resets the strategy's internal state. Called when looting ends or
     * is interrupted.
     */
    void reset();

    /**
     * Returns the number of items taken from the current chest.
     *
     * @return items taken count
     */
    int getItemsTaken();

    /**
     * Result of a single loot tick.
     */
    enum LootTickResult {
        /** Walking toward the chest. */
        APPROACHING,
        /** Opening the chest / breaking it. */
        OPENING,
        /** Taking items from the chest. */
        LOOTING,
        /** Finished looting this chest. */
        COMPLETE,
        /** Loot failed (chest gone, inventory full, interrupted). */
        FAILED
    }
}
