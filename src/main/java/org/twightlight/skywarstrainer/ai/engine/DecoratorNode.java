package org.twightlight.skywarstrainer.ai.engine;

import org.twightlight.skywarstrainer.bot.TrainerBot;

import javax.annotation.Nonnull;

/**
 * Abstract base class for all decorator nodes.
 *
 * <p>A decorator wraps exactly one child node and modifies its behaviour
 * (inverts result, repeats execution, adds a cooldown, etc.).</p>
 *
 * <p>Concrete implementations include:
 * {@link InverterDecorator}, {@link RepeaterDecorator}, {@link CooldownDecorator},
 * {@link ChanceDecorator}, {@link TimeoutDecorator}, and
 * {@link DifficultyGateDecorator}.</p>
 */
public abstract class DecoratorNode extends BehaviorNode {

    /** The single child node being decorated. */
    protected final BehaviorNode child;

    /**
     * Creates a decorator wrapping the given child.
     *
     * @param name  debug name for this decorator
     * @param child the child node to decorate
     */
    protected DecoratorNode(@Nonnull String name, @Nonnull BehaviorNode child) {
        super(name);
        this.child = child;
    }

    @Override
    public void reset(@Nonnull TrainerBot bot) {
        child.reset(bot);
    }

    /**
     * Returns the child being decorated.
     *
     * @return the child node
     */
    @Nonnull
    public BehaviorNode getChild() {
        return child;
    }
}
