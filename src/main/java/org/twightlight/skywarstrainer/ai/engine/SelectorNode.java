package org.twightlight.skywarstrainer.ai.engine;

import org.twightlight.skywarstrainer.bot.TrainerBot;

import javax.annotation.Nonnull;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Composite node that tries children left-to-right and returns SUCCESS on the
 * first child that succeeds (or is RUNNING). Returns FAILURE only if every
 * child fails.
 *
 * <p>Semantics: "Try option A. If A fails, try option B. If B fails, try C…"</p>
 *
 * <p>Memory behaviour: if a child returns RUNNING, the selector remembers which
 * child was running and resumes from that child on the next tick (it does not
 * restart from the first child). This is the standard "non-restart" selector.</p>
 */
public final class SelectorNode extends BehaviorNode {

    private final List<BehaviorNode> children;

    /** Index of the currently-running child (-1 = start from beginning). */
    private int runningIndex = -1;

    /**
     * Creates a selector with the given children.
     *
     * @param name     debug name
     * @param children the child nodes, tried left-to-right
     */
    public SelectorNode(@Nonnull String name, @Nonnull BehaviorNode... children) {
        super(name);
        this.children = new ArrayList<>(Arrays.asList(children));
    }

    /**
     * Creates a selector with a mutable child list.
     *
     * @param name     debug name
     * @param children list of child nodes
     */
    public SelectorNode(@Nonnull String name, @Nonnull List<BehaviorNode> children) {
        super(name);
        this.children = new ArrayList<>(children);
    }

    @Override
    @Nonnull
    public NodeStatus tick(@Nonnull TrainerBot bot) {
        // Resume from the running child if there is one; otherwise start from 0
        int startIndex = (runningIndex >= 0) ? runningIndex : 0;

        for (int i = startIndex; i < children.size(); i++) {
            NodeStatus status = children.get(i).tick(bot);
            switch (status) {
                case SUCCESS:
                    runningIndex = -1; // Reset for next tree tick
                    return NodeStatus.SUCCESS;
                case RUNNING:
                    runningIndex = i; // Remember where we are
                    return NodeStatus.RUNNING;
                case FAILURE:
                    // Try the next child
                    break;
            }
        }

        // All children failed
        runningIndex = -1;
        return NodeStatus.FAILURE;
    }

    @Override
    public void reset(@Nonnull TrainerBot bot) {
        runningIndex = -1;
        for (BehaviorNode child : children) {
            child.reset(bot);
        }
    }

    /**
     * Adds a child node at the end of the list.
     *
     * @param child the child to add
     */
    public void addChild(@Nonnull BehaviorNode child) {
        children.add(child);
    }
}

