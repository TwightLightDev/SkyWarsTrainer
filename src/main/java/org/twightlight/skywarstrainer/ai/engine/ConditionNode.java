package org.twightlight.skywarstrainer.ai.engine;

import org.twightlight.skywarstrainer.bot.TrainerBot;

import javax.annotation.Nonnull;
import java.util.function.Predicate;

/**
 * Leaf node that evaluates a boolean condition against the bot.
 *
 * <p>Returns {@link NodeStatus#SUCCESS} if the predicate is true,
 * {@link NodeStatus#FAILURE} if false. Never returns RUNNING (conditions
 * are instantaneous checks).</p>
 *
 * <p>Conditions are expressed as Java 8 lambdas or method references:</p>
 * <pre>
 *   new ConditionNode("hasSword", bot ->
 *       bot.getPlayerEntity() != null
 *       &amp;&amp; bot.getPlayerEntity().getInventory().getItemInHand().getType().name().contains("SWORD"))
 * </pre>
 */
public final class ConditionNode extends BehaviorNode {

    private final Predicate<TrainerBot> condition;

    /**
     * Creates a condition node.
     *
     * @param name      debug name (describe WHAT is being checked)
     * @param condition the predicate; must be cheap to evaluate (runs every tick)
     */
    public ConditionNode(@Nonnull String name, @Nonnull Predicate<TrainerBot> condition) {
        super(name);
        this.condition = condition;
    }

    @Override
    @Nonnull
    public NodeStatus tick(@Nonnull TrainerBot bot) {
        return condition.test(bot) ? NodeStatus.SUCCESS : NodeStatus.FAILURE;
    }

    // Conditions have no internal state; reset is a no-op.
}

