package org.twightlight.skywarstrainer.ai.engine;

import org.twightlight.skywarstrainer.bot.TrainerBot;

import javax.annotation.Nonnull;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Composite node that ticks ALL children every tick regardless of individual results.
 *
 * <p>Success and failure policies are configurable:</p>
 * <ul>
 *   <li>{@link Policy#ALL_SUCCESS}: returns SUCCESS only when ALL children succeed;
 *       FAILURE if any fail.</li>
 *   <li>{@link Policy#ONE_SUCCESS}: returns SUCCESS when at least one child succeeds;
 *       FAILURE only if all fail.</li>
 * </ul>
 *
 * <p>Returns RUNNING while neither success nor failure threshold is met.</p>
 *
 * <p>Typical usage: run combat-targeting and combat-execution in parallel so that
 * the target is always re-evaluated while the bot is executing attack moves.</p>
 */
public final class ParallelNode extends BehaviorNode {

    /**
     * Determines what constitutes "success" for the parallel node.
     */
    public enum Policy {
        /** All children must succeed for the parallel to succeed. */
        ALL_SUCCESS,
        /** At least one child must succeed for the parallel to succeed. */
        ONE_SUCCESS
    }

    private final List<BehaviorNode> children;
    private final Policy successPolicy;

    /**
     * Creates a parallel node.
     *
     * @param name          debug name
     * @param successPolicy when to consider the parallel node successful
     * @param children      child nodes, all ticked each time
     */
    public ParallelNode(@Nonnull String name, @Nonnull Policy successPolicy,
                        @Nonnull BehaviorNode... children) {
        super(name);
        this.successPolicy = successPolicy;
        this.children = new ArrayList<>(Arrays.asList(children));
    }

    @Override
    @Nonnull
    public NodeStatus tick(@Nonnull TrainerBot bot) {
        int successCount = 0;
        int failureCount = 0;

        for (BehaviorNode child : children) {
            NodeStatus status = child.tick(bot);
            if (status == NodeStatus.SUCCESS) successCount++;
            else if (status == NodeStatus.FAILURE) failureCount++;
            // RUNNING is neither; just continue ticking
        }

        switch (successPolicy) {
            case ALL_SUCCESS:
                if (successCount == children.size())    return NodeStatus.SUCCESS;
                if (failureCount > 0)                    return NodeStatus.FAILURE;
                return NodeStatus.RUNNING;

            case ONE_SUCCESS:
                if (successCount > 0)                    return NodeStatus.SUCCESS;
                if (failureCount == children.size())     return NodeStatus.FAILURE;
                return NodeStatus.RUNNING;

            default:
                return NodeStatus.RUNNING;
        }
    }

    @Override
    public void reset(@Nonnull TrainerBot bot) {
        for (BehaviorNode child : children) {
            child.reset(bot);
        }
    }
}

