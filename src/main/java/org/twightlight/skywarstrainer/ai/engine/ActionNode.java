package org.twightlight.skywarstrainer.ai.engine;

import org.twightlight.skywarstrainer.SkyWarsTrainer;
import org.twightlight.skywarstrainer.bot.TrainerBot;

import javax.annotation.Nonnull;
import java.util.function.Function;

/**
 * Leaf node that executes a game action against the bot.
 *
 * <p>The action is a {@link Function} from {@link TrainerBot} to {@link NodeStatus}.
 * Actions may return RUNNING for multi-tick operations (e.g., pathfinding,
 * eating food, placing multiple blocks).</p>
 *
 * <p>Example:</p>
 * <pre>
 *   new ActionNode("eatFood", bot -> {
 *       // Phase 5: FoodHandler.eat()
 *       return bot.getFoodHandler() != null
 *           ? bot.getFoodHandler().eatIfNeeded()
 *           : NodeStatus.FAILURE;
 *   })
 * </pre>
 */
public final class ActionNode extends BehaviorNode {

    private final Function<TrainerBot, NodeStatus> action;

    /**
     * Creates an action node.
     *
     * @param name   debug name (describe WHAT action is performed)
     * @param action the action function; may return SUCCESS, FAILURE, or RUNNING
     */
    public ActionNode(@Nonnull String name, @Nonnull Function<TrainerBot, NodeStatus> action) {
        super(name);
        this.action = action;
    }

    @Override
    @Nonnull
    public NodeStatus tick(@Nonnull TrainerBot bot) {
        try {
            return action.apply(bot);
        } catch (Exception ex) {
            // Safety net: log and fail gracefully rather than crashing the entire tree
            if (bot.getProfile().isDebugMode()) {
                SkyWarsTrainer.getInstance().getLogger().warning(
                        "[BT] ActionNode '" + name + "' threw: " + ex.getMessage());
            }
            return NodeStatus.FAILURE;
        }
    }

    // Actions may carry RUNNING state internally (inside the lambda closure);
    // reset() is provided for completeness but the lambda must handle its own cleanup.
}
