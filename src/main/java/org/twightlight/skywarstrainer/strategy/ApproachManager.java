package org.twightlight.skywarstrainer.strategy;

import org.bukkit.entity.LivingEntity;
import org.twightlight.skywarstrainer.bot.TrainerBot;
import org.twightlight.skywarstrainer.config.DifficultyConfig.DifficultyProfile;
import org.twightlight.skywarstrainer.strategy.approaches.*;
import org.twightlight.skywarstrainer.util.DebugLogger;
import org.twightlight.skywarstrainer.util.RandomUtil;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;

/**
 * Central manager that owns all approach strategies and orchestrates
 * the approach lifecycle: evaluate → select → execute → arrive/fail.
 *
 * <p>Called by the HUNTING state behavior tree when the target is on
 * a different island from the bot.</p>
 */
public class ApproachManager {

    private final TrainerBot bot;

    /** All registered approach strategies. */
    private final List<ApproachStrategy> strategies;

    /** The currently active approach strategy. */
    private ApproachStrategy activeStrategy;

    /** The current target being approached. */
    private LivingEntity currentTarget;

    /** Whether an approach is currently in progress. */
    private boolean active;

    /** Reusable context for evaluation. */
    private final ApproachContext context;

    /**
     * Creates a new ApproachManager for the given bot.
     *
     * @param bot the owning trainer bot
     */
    public ApproachManager(@Nonnull TrainerBot bot) {
        this.bot = bot;
        this.strategies = new ArrayList<>();
        this.context = new ApproachContext();
        this.active = false;

        registerStrategies();
    }

    /**
     * Registers all approach strategy implementations.
     */
    private void registerStrategies() {
        strategies.add(new DirectRushApproach());
        strategies.add(new DiagonalApproach());
        strategies.add(new VerticalApproach());
        strategies.add(new SplitPathApproach());
        strategies.add(new FlankingApproach());
        strategies.add(new PearlApproach());
    }

    /**
     * Begins an approach toward the given target. Evaluates all strategies,
     * selects the best one, calculates a path, and starts execution.
     *
     * @param target the target to approach
     * @return true if an approach strategy was selected and started
     */
    public boolean startApproach(@Nonnull LivingEntity target) {
        this.currentTarget = target;
        context.populate(bot, target);

        // Evaluate all strategies
        ApproachStrategy best = selectBestStrategy(target);
        if (best == null) {
            DebugLogger.log(bot, "ApproachManager: no viable approach strategy found");
            return false;
        }

        // Initialize and start
        best.reset();
        best.calculateApproachPath(bot, target);
        this.activeStrategy = best;
        this.active = true;

        DebugLogger.log(bot, "ApproachManager: starting %s approach to %s (dist=%.1f)",
                best.getName(), target.getName(), context.distanceToTarget);

        return true;
    }

    /**
     * Ticks the active approach strategy.
     *
     * @return the approach result, or FAILED if no approach is active
     */
    @Nonnull
    public ApproachTickResult tick() {
        if (!active || activeStrategy == null) {
            return ApproachTickResult.FAILED;
        }

        // Re-validate target
        if (currentTarget == null || currentTarget.isDead()) {
            cancelApproach();
            return ApproachTickResult.FAILED;
        }

        ApproachTickResult result = activeStrategy.tick(bot);

        switch (result) {
            case ARRIVED:
            case FAILED:
            case INTERRUPTED:
                active = false;
                DebugLogger.log(bot, "ApproachManager: approach %s with result %s",
                        activeStrategy.getName(), result.name());
                break;
            case IN_PROGRESS:
                // Continue
                break;
        }

        return result;
    }

    /**
     * Cancels the current approach.
     */
    public void cancelApproach() {
        if (activeStrategy != null) {
            activeStrategy.reset();
        }
        activeStrategy = null;
        currentTarget = null;
        active = false;
    }

    /**
     * Selects the best approach strategy using weighted priority scoring.
     *
     * <p>For each viable strategy:
     * 1. Check shouldUse() — is it viable?
     * 2. Get base priority
     * 3. Apply personality multipliers
     * 4. Apply situation multipliers
     * 5. Apply decision quality noise
     * 6. Pick highest</p>
     */
    @Nullable
    private ApproachStrategy selectBestStrategy(@Nonnull LivingEntity target) {
        DifficultyProfile diff = bot.getDifficultyProfile();
        double decisionQuality = diff.getDecisionQuality();

        ApproachStrategy best = null;
        double bestScore = Double.NEGATIVE_INFINITY;

        for (ApproachStrategy strategy : strategies) {
            if (!strategy.shouldUse(bot, target, context)) continue;

            double score = strategy.getPriority(bot);

            // Apply decision quality noise
            double noiseRange = (1.0 - decisionQuality) * 0.3;
            score += RandomUtil.nextDouble(-noiseRange, noiseRange);

            if (score > bestScore) {
                bestScore = score;
                best = strategy;
            }
        }

        return best;
    }

    // ─── Accessors ──────────────────────────────────────────────

    /** @return true if an approach is currently in progress */
    public boolean isActive() { return active; }

    /** @return the currently active approach strategy */
    @Nullable
    public ApproachStrategy getActiveStrategy() { return activeStrategy; }

    /** @return the current target being approached */
    @Nullable
    public LivingEntity getCurrentTarget() { return currentTarget; }
}
