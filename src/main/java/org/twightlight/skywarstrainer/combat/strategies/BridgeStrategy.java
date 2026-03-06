package org.twightlight.skywarstrainer.bridging.strategies;

import org.bukkit.Location;
import org.twightlight.skywarstrainer.bot.TrainerBot;

import javax.annotation.Nonnull;

/**
 * Interface for all bridge construction strategies.
 *
 * <p>Each strategy represents a specific bridging technique (normal shift-bridge,
 * speed bridge, ninja bridge, god bridge, etc.). The BridgeEngine evaluates which
 * strategy to use based on the bot's difficulty, personality, and the current
 * situation, then delegates block-by-block bridge construction to the selected
 * strategy.</p>
 *
 * <p>Strategies are responsible for:
 * <ul>
 *   <li>Setting the correct look angles for block placement</li>
 *   <li>Managing sneak/unsneak timing</li>
 *   <li>Controlling movement direction and speed during bridging</li>
 *   <li>Determining the next block placement position</li>
 *   <li>Handling bridge failure simulation (missing a block)</li>
 * </ul></p>
 *
 * <p>Each strategy returns a {@link BridgeTickResult} per tick indicating whether
 * a block was placed, the bridge is still in progress, or bridging failed.</p>
 */
public interface BridgeStrategy {

    /**
     * Returns the unique name of this bridge strategy.
     *
     * @return the strategy name
     */
    @Nonnull
    String getName();

    /**
     * Returns the minimum difficulty level required to use this strategy.
     * The BridgeEngine checks this against the bot's {@code bridgeMaxType}
     * parameter and won't select strategies above the bot's capability.
     *
     * @return the minimum difficulty name (e.g., "NORMAL", "NINJA", "GOD")
     */
    @Nonnull
    String getRequiredBridgeType();

    /**
     * Returns the approximate blocks-per-second speed of this strategy.
     * Used by the BridgeEngine to estimate bridge completion time and
     * to match the strategy's speed to the bot's {@code bridgeSpeed} parameter.
     *
     * @return the base speed in blocks per second
     */
    double getBaseSpeed();

    /**
     * Initializes the strategy for a new bridge. Called once when the bot
     * begins bridging. Sets up the starting position, direction, and
     * any internal state needed for the bridge.
     *
     * @param bot       the bot performing the bridge
     * @param start     the starting position (where the bot currently is)
     * @param direction the direction to bridge toward (normalized XZ vector)
     */
    void initialize(@Nonnull TrainerBot bot, @Nonnull Location start,
                    @Nonnull org.bukkit.util.Vector direction);

    /**
     * Ticks one frame of the bridging sequence. Handles movement, look angles,
     * sneak state, and block placement for this tick.
     *
     * <p>Returns a {@link BridgeTickResult} indicating what happened:
     * <ul>
     *   <li>{@code PLACED}: a block was placed this tick</li>
     *   <li>{@code MOVING}: the bot is repositioning for the next placement</li>
     *   <li>{@code FAILED}: the bot failed to place (fell, ran out of blocks, etc.)</li>
     *   <li>{@code COMPLETE}: the bridge has reached the target destination</li>
     * </ul></p>
     *
     * @param bot the bot
     * @return the result of this tick
     */
    @Nonnull
    BridgeTickResult tick(@Nonnull TrainerBot bot);

    /**
     * Resets the strategy's internal state. Called when bridging ends or
     * is interrupted.
     */
    void reset();

    /**
     * Returns the number of blocks this strategy has placed in the current
     * bridge sequence.
     *
     * @return blocks placed count
     */
    int getBlocksPlaced();

    /**
     * Result of a single bridge tick.
     */
    enum BridgeTickResult {
        /** A block was successfully placed this tick. */
        PLACED,
        /** The bot is moving into position for the next placement. */
        MOVING,
        /** The bot failed to place (fell off, no blocks, error). */
        FAILED,
        /** The bridge has reached the target destination. */
        COMPLETE
    }
}
