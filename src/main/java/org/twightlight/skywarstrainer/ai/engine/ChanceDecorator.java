package org.twightlight.skywarstrainer.ai.engine;

import org.twightlight.skywarstrainer.bot.TrainerBot;
import org.twightlight.skywarstrainer.util.RandomUtil;

import javax.annotation.Nonnull;

/**
 * Decorator that only runs the child with a given probability.
 *
 * <p>On each call to {@link #tick(TrainerBot)}, a random roll is made.
 * If the roll exceeds the chance threshold, the node immediately returns FAILURE
 * without running the child. Otherwise the child runs normally.</p>
 *
 * <p>This adds stochastic behaviour to bot actions, making them feel less robotic.
 * For example: only 70 % of the time does the bot attempt a block-hit after an
 * attack, simulating human inconsistency.</p>
 *
 * <p>When the child is already RUNNING from a previous tick, the roll is skipped
 * and the child continues — we don't interrupt mid-execution.</p>
 */
public final class ChanceDecorator extends DecoratorNode {

    /** Probability in [0.0, 1.0] that the child will run. */
    private double chance;

    /** Whether the child started running (roll already passed for this execution). */
    private boolean childRunning = false;

    /**
     * Creates a chance decorator.
     *
     * @param name   debug name
     * @param child  the child to gate probabilistically
     * @param chance probability [0.0, 1.0] the child runs; 1.0 = always, 0.0 = never
     */
    public ChanceDecorator(@Nonnull String name, @Nonnull BehaviorNode child, double chance) {
        super(name, child);
        this.chance = Math.max(0.0, Math.min(1.0, chance));
    }

    @Override
    @Nonnull
    public NodeStatus tick(@Nonnull TrainerBot bot) {
        // If child is mid-execution, don't re-roll — let it finish
        if (childRunning) {
            NodeStatus status = child.tick(bot);
            if (status != NodeStatus.RUNNING) {
                childRunning = false;
            }
            return status;
        }

        // Roll the dice
        if (RandomUtil.nextDouble() > chance) {
            return NodeStatus.FAILURE; // Missed the chance
        }

        childRunning = true;
        NodeStatus status = child.tick(bot);
        if (status != NodeStatus.RUNNING) {
            childRunning = false;
        }
        return status;
    }

    @Override
    public void reset(@Nonnull TrainerBot bot) {
        childRunning = false;
        super.reset(bot);
    }

    /**
     * Updates the chance value at runtime (e.g., personality modifiers applied).
     *
     * @param chance new probability [0.0, 1.0]
     */
    public void setChance(double chance) {
        this.chance = Math.max(0.0, Math.min(1.0, chance));
    }

    /**
     * Returns the current probability.
     *
     * @return chance [0.0, 1.0]
     */
    public double getChance() {
        return chance;
    }
}

