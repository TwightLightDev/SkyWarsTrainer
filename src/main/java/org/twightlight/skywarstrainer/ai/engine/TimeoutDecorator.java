package org.twightlight.skywarstrainer.ai.engine;

import org.twightlight.skywarstrainer.bot.TrainerBot;

import javax.annotation.Nonnull;

/**
 * Decorator that fails the child if it has been RUNNING for more than N ticks.
 *
 * <p>Prevents the bot from getting stuck in a state indefinitely. For example,
 * "pathfind to chest" has a timeout — if the bot can't reach the chest within
 * 100 ticks (5 seconds), it gives up and tries something else.</p>
 */
public final class TimeoutDecorator extends DecoratorNode {

    private final int timeoutTicks;
    private long startTick = -1L;

    /**
     * Creates a timeout decorator.
     *
     * @param name         debug name
     * @param child        the child to time-limit
     * @param timeoutTicks maximum ticks the child may be RUNNING
     */
    public TimeoutDecorator(@Nonnull String name, @Nonnull BehaviorNode child,
                            int timeoutTicks) {
        super(name, child);
        this.timeoutTicks = timeoutTicks;
    }

    @Override
    @Nonnull
    public NodeStatus tick(@Nonnull TrainerBot bot) {
        long currentTick = bot.getLocalTickCount();

        // Latch start tick on first execution
        if (startTick < 0L) {
            startTick = currentTick;
        }

        // Check timeout
        if ((currentTick - startTick) >= timeoutTicks) {
            reset(bot);
            return NodeStatus.FAILURE; // Timed out
        }

        NodeStatus status = child.tick(bot);
        if (status != NodeStatus.RUNNING) {
            startTick = -1L; // Reset for next use
        }
        return status;
    }

    @Override
    public void reset(@Nonnull TrainerBot bot) {
        startTick = -1L;
        super.reset(bot);
    }
}

