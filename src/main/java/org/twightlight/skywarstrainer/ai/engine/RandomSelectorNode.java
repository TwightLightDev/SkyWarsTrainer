package org.twightlight.skywarstrainer.ai.engine;

import org.twightlight.skywarstrainer.bot.TrainerBot;

import javax.annotation.Nonnull;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * A Selector that shuffles its children order before each fresh evaluation.
 *
 * <p>This adds unpredictability to the bot's decision-making. For example, when
 * choosing between two equally valid loot strategies, the bot will sometimes pick
 * one and sometimes the other, making it less predictable.</p>
 *
 * <p>The shuffle only happens when the selector starts fresh (not while a child is
 * RUNNING — in that case execution resumes on the same child).</p>
 */
public final class RandomSelectorNode extends BehaviorNode {

    private final List<BehaviorNode> originalChildren;
    /** Shuffled copy used for the current evaluation. */
    private final List<BehaviorNode> shuffledChildren;
    private int runningIndex = -1;

    /**
     * Creates a random selector.
     *
     * @param name     debug name
     * @param children the children to randomly order on each fresh evaluation
     */
    public RandomSelectorNode(@Nonnull String name, @Nonnull BehaviorNode... children) {
        super(name);
        this.originalChildren = new ArrayList<>(Arrays.asList(children));
        this.shuffledChildren = new ArrayList<>(originalChildren);
    }

    @Override
    @Nonnull
    public NodeStatus tick(@Nonnull TrainerBot bot) {
        // On a fresh start, shuffle the children
        if (runningIndex < 0) {
            shuffledChildren.clear();
            shuffledChildren.addAll(originalChildren);
            Collections.shuffle(shuffledChildren);
        }

        int startIndex = (runningIndex >= 0) ? runningIndex : 0;

        for (int i = startIndex; i < shuffledChildren.size(); i++) {
            NodeStatus status = shuffledChildren.get(i).tick(bot);
            switch (status) {
                case SUCCESS:
                    runningIndex = -1;
                    return NodeStatus.SUCCESS;
                case RUNNING:
                    runningIndex = i;
                    return NodeStatus.RUNNING;
                case FAILURE:
                    break; // Try next
            }
        }

        runningIndex = -1;
        return NodeStatus.FAILURE;
    }

    @Override
    public void reset(@Nonnull TrainerBot bot) {
        runningIndex = -1;
        for (BehaviorNode child : originalChildren) {
            child.reset(bot);
        }
    }
}

