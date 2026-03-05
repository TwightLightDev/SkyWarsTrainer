package org.twightlight.skywarstrainer.ai.engine;

import org.twightlight.skywarstrainer.bot.TrainerBot;

import javax.annotation.Nonnull;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Composite node that runs children left-to-right and returns FAILURE on the
 * first child that fails. Returns SUCCESS only if every child succeeds.
 *
 * <p>Semantics: "Do A, then B, then C. If any step fails, the whole sequence fails."</p>
 *
 * <p>Memory behaviour: if a child returns RUNNING, the sequence remembers the index
 * and resumes from that child on the next tick.</p>
 */
public final class SequenceNode extends BehaviorNode {

    private final List<BehaviorNode> children;

    /** Index of the currently-running child (-1 = start from beginning). */
    private int runningIndex = -1;

    /**
     * Creates a sequence with the given children.
     *
     * @param name     debug name
     * @param children child nodes executed in order
     */
    public SequenceNode(@Nonnull String name, @Nonnull BehaviorNode... children) {
        super(name);
        this.children = new ArrayList<>(Arrays.asList(children));
    }

    /**
     * Creates a sequence with a mutable child list.
     *
     * @param name     debug name
     * @param children list of child nodes
     */
    public SequenceNode(@Nonnull String name, @Nonnull List<BehaviorNode> children) {
        super(name);
        this.children = new ArrayList<>(children);
    }

    @Override
    @Nonnull
    public NodeStatus tick(@Nonnull TrainerBot bot) {
        int startIndex = (runningIndex >= 0) ? runningIndex : 0;

        for (int i = startIndex; i < children.size(); i++) {
            NodeStatus status = children.get(i).tick(bot);
            switch (status) {
                case FAILURE:
                    runningIndex = -1;
                    return NodeStatus.FAILURE;
                case RUNNING:
                    runningIndex = i;
                    return NodeStatus.RUNNING;
                case SUCCESS:
                    // Advance to the next child
                    break;
            }
        }

        // All children succeeded
        runningIndex = -1;
        return NodeStatus.SUCCESS;
    }

    @Override
    public void reset(@Nonnull TrainerBot bot) {
        runningIndex = -1;
        for (BehaviorNode child : children) {
            child.reset(bot);
        }
    }

    /**
     * Appends a child to this sequence.
     *
     * @param child the child node to add
     */
    public void addChild(@Nonnull BehaviorNode child) {
        children.add(child);
    }
}

