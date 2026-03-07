package org.twightlight.skywarstrainer.movement.positional;

import org.twightlight.skywarstrainer.bot.TrainerBot;
import org.twightlight.skywarstrainer.util.DebugLogger;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * Evaluates and manages the active positional strategy. Feeds into the
 * DecisionEngine by providing utility score modifiers from the active strategy.
 *
 * <p>Evaluated every 40-60 ticks (slower than combat — macro-level decisions).</p>
 */
public class PositionalEngine {

    private final TrainerBot bot;
    private final List<PositionalStrategy> strategies;
    private PositionalStrategy activeStrategy;

    /**
     * Creates a new PositionalStrategyManager, registering all implementations.
     *
     * @param bot the owning bot
     */
    public PositionalEngine(@Nonnull TrainerBot bot) {
        this.bot = bot;
        this.strategies = new ArrayList<>();

        strategies.add(new HighGroundControl());
        strategies.add(new IslandRotation());
        strategies.add(new ThirdPartyPositioning());
        strategies.add(new MidControl());
    }

    /**
     * Evaluates all strategies and selects the best applicable one.
     * Called every 40-60 ticks.
     */
    public void tick() {
        // Check if current strategy completed
        if (activeStrategy != null && activeStrategy.isComplete()) {
            DebugLogger.log(bot, "Positional strategy completed: %s", activeStrategy.getName());
            activeStrategy.reset();
            activeStrategy = null;
        }

        // Tick active strategy
        if (activeStrategy != null) {
            activeStrategy.tick(bot);
            return;
        }

        // Evaluate for new strategy
        PositionalStrategy best = null;
        for (PositionalStrategy strategy : strategies) {
            if (strategy.shouldActivate(bot)) {
                best = strategy;
                break; // First match wins (strategies are in priority order)
            }
        }

        if (best != null) {
            activeStrategy = best;
            DebugLogger.log(bot, "Positional strategy activated: %s", best.getName());
        }
    }

    /**
     * Returns the active strategy, or null.
     *
     * @return the active positional strategy
     */
    @Nullable
    public PositionalStrategy getActiveStrategy() { return activeStrategy; }

    /**
     * Returns utility score modifiers from the active strategy.
     * Empty map if no strategy is active.
     *
     * @return the utility bonuses
     */
    @Nonnull
    public Map<String, Double> getActiveUtilityBonuses() {
        if (activeStrategy == null) return Collections.emptyMap();
        return activeStrategy.getUtilityBonus();
    }

    /** @return true if a positional strategy is currently active */
    public boolean hasActiveStrategy() { return activeStrategy != null; }
}
