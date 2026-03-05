package org.twightlight.skywarstrainer.ai.decision;

import javax.annotation.Nonnull;

/**
 * Functional interface for a single utility consideration.
 *
 * <p>A consideration takes a {@link DecisionContext} snapshot and returns a
 * score in [0.0, 1.0] representing how desirable its associated factor makes
 * a particular action. Multiple considerations are combined (weighted sum)
 * to produce the final utility score for an action.</p>
 *
 * <p>Example: {@code HealthConsideration} returns a high value when health is low,
 * which boosts FLEE and HEAL action scores.</p>
 *
 * <p>Considerations should be stateless — all data comes from the context.
 * This allows them to be shared across bots and evaluated concurrently.</p>
 */
@FunctionalInterface
public interface UtilityScorer {

    /**
     * Evaluates this consideration and returns a score in [0.0, 1.0].
     *
     * @param context the current world-state snapshot
     * @return the consideration score; higher = more desirable
     */
    double score(@Nonnull DecisionContext context);

    /**
     * Returns a human-readable name for debug output. Default uses class name.
     *
     * @return the consideration name
     */
    default String getName() {
        return getClass().getSimpleName();
    }
}
