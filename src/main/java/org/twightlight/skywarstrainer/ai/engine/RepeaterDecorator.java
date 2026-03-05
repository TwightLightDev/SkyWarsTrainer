package org.twightlight.skywarstrainer.ai.engine;

import org.twightlight.skywarstrainer.bot.TrainerBot;

import javax.annotation.Nonnull;

/**
 * Decorator that repeats the child node a fixed number of times.
 *
 * <p>Returns SUCCESS after completing the required repetitions.
 * Returns FAILURE immediately if the child returns FAILURE (unless
 * {@code ignoreFailure} is true, in which case failures are counted
 * as successes for repetition purposes).</p>
 *
 * <p>Pass {@code maxRepeats = -1} for infinite repeating (never returns SUCCESS;
 * only RUNNING or FAILURE — useful for "always do this" behaviours).</p>
 */
public final class RepeaterDecorator extends DecoratorNode {

    private final int maxRepeats;
    private final boolean ignoreFailure;
    private int completedRepeats = 0;

    /**
     * Creates a repeater.
     *
     * @param name          debug name
     * @param child         the child to repeat
     * @param maxRepeats    number of times to repeat; -1 for infinite
     * @param ignoreFailure if true, child FAILURE is treated as a completed repeat
     */
    public RepeaterDecorator(@Nonnull String name, @Nonnull BehaviorNode child,
                             int maxRepeats, boolean ignoreFailure) {
        super(name, child);
        this.maxRepeats    = maxRepeats;
        this.ignoreFailure = ignoreFailure;
    }

    @Override
    @Nonnull
    public NodeStatus tick(@Nonnull TrainerBot bot) {
        NodeStatus status = child.tick(bot);

        if (status == NodeStatus.RUNNING) {
            return NodeStatus.RUNNING;
        }
        if (status == NodeStatus.FAILURE && !ignoreFailure) {
            reset(bot);
            return NodeStatus.FAILURE;
        }

        // Count this as a completed repeat
        completedRepeats++;
        child.reset(bot); // Reset child for next iteration

        if (maxRepeats >= 0 && completedRepeats >= maxRepeats) {
            completedRepeats = 0;
            return NodeStatus.SUCCESS;
        }

        // Still repeating
        return NodeStatus.RUNNING;
    }

    @Override
    public void reset(@Nonnull TrainerBot bot) {
        completedRepeats = 0;
        super.reset(bot);
    }
}

