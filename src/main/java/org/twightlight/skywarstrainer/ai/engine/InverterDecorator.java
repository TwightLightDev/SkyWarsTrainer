package org.twightlight.skywarstrainer.ai.engine;

import org.twightlight.skywarstrainer.bot.TrainerBot;

import javax.annotation.Nonnull;

/**
 * Decorator that inverts SUCCESS to FAILURE and vice versa.
 * RUNNING is passed through unchanged.
 *
 * <p>Use case: "Succeed if the child FAILS" — e.g., a condition check like
 * "no enemy in range" can be expressed as InverterDecorator("no enemy",
 * ConditionNode("enemy in range", ...)).</p>
 */
public final class InverterDecorator extends DecoratorNode {

    /**
     * Creates an inverter wrapping the given child.
     *
     * @param name  debug name
     * @param child the child node whose result is inverted
     */
    public InverterDecorator(@Nonnull String name, @Nonnull BehaviorNode child) {
        super(name, child);
    }

    @Override
    @Nonnull
    public NodeStatus tick(@Nonnull TrainerBot bot) {
        NodeStatus status = child.tick(bot);
        switch (status) {
            case SUCCESS: return NodeStatus.FAILURE;
            case FAILURE: return NodeStatus.SUCCESS;
            default:      return NodeStatus.RUNNING; // Pass RUNNING through
        }
    }
}
