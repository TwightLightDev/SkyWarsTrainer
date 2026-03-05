package org.twightlight.skywarstrainer.ai.engine;

import org.twightlight.skywarstrainer.bot.TrainerBot;
import org.twightlight.skywarstrainer.config.DifficultyConfig.DifficultyProfile;

import javax.annotation.Nonnull;

/**
 * Decorator that only runs the child if the bot's difficulty meets a minimum threshold.
 *
 * <p>Allows building a single unified behavior tree that automatically disables
 * advanced techniques for lower-difficulty bots. For example, a GodBridge node is
 * wrapped in a DifficultyGateDecorator with threshold "decisionQuality &ge; 0.9"
 * so only EXPERT bots attempt it.</p>
 *
 * <p>The threshold is expressed as a minimum value for one of the difficulty
 * parameters (e.g., {@code aimAccuracy >= 0.8}).</p>
 */
public final class DifficultyGateDecorator extends DecoratorNode {

    /**
     * Functional interface for the gate predicate.
     * Receives the bot's DifficultyProfile and returns true if the child should run.
     */
    @FunctionalInterface
    public interface DifficultyPredicate {
        /**
         * Evaluates whether the difficulty meets the gate condition.
         *
         * @param profile the bot's difficulty profile
         * @return true if the child should be allowed to run
         */
        boolean test(@Nonnull DifficultyProfile profile);
    }

    private final DifficultyPredicate gatePredicate;
    private final String gateDescription;

    /**
     * Creates a difficulty gate decorator.
     *
     * @param name             debug name
     * @param child            the child to gate
     * @param gateDescription  human-readable description of the gate condition (for debug)
     * @param gatePredicate    the predicate; returns true if the child is allowed to run
     */
    public DifficultyGateDecorator(@Nonnull String name, @Nonnull BehaviorNode child,
                                   @Nonnull String gateDescription,
                                   @Nonnull DifficultyPredicate gatePredicate) {
        super(name, child);
        this.gateDescription = gateDescription;
        this.gatePredicate   = gatePredicate;
    }

    @Override
    @Nonnull
    public NodeStatus tick(@Nonnull TrainerBot bot) {
        DifficultyProfile profile = bot.getDifficultyProfile();
        if (!gatePredicate.test(profile)) {
            // Difficulty too low — treat as failure so parent tries a fallback
            return NodeStatus.FAILURE;
        }
        return child.tick(bot);
    }

    /**
     * Returns the human-readable description of this gate's condition.
     *
     * @return the gate description
     */
    @Nonnull
    public String getGateDescription() {
        return gateDescription;
    }
}
