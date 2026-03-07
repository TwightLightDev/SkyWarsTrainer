package org.twightlight.skywarstrainer.movement.positional;

import org.bukkit.Location;
import org.twightlight.skywarstrainer.ai.decision.DecisionContext;
import org.twightlight.skywarstrainer.awareness.IslandGraph;
import org.twightlight.skywarstrainer.bot.TrainerBot;
import org.twightlight.skywarstrainer.util.RandomUtil;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Island rotation: avoid direct confrontation by rotating between islands
 * to collect loot. Once well-geared, switch to aggressive play.
 */
public class IslandRotation implements PositionalStrategy {

    private boolean complete;
    private int ticksActive;

    @Nonnull @Override
    public String getName() { return "IslandRotation"; }

    @Override
    public boolean shouldActivate(@Nonnull TrainerBot bot) {
        double tendency = bot.getDifficultyProfile().getIslandRotationTendency();
        if (!RandomUtil.chance(tendency)) return false;

        // Should be under-geared and in early/mid game
        if (bot.getDecisionEngine() != null) {
            DecisionContext ctx = bot.getDecisionEngine().getContext();
            return ctx.equipmentScore < 0.5 && ctx.gameProgress < 0.6;
        }
        return false;
    }

    @Nullable @Override
    public Location getTargetPosition(@Nonnull TrainerBot bot) {
        // Find an unlooted island via IslandGraph
        IslandGraph graph = bot.getIslandGraph();
        if (graph != null) {
            List<IslandGraph.Island> islands = graph.getIslands();
            for (IslandGraph.Island island : islands) {
                if (island.center != null && !graph.isOnMidIsland(island.center)) {
                    return island.center;
                }
            }
        }
        return null;
    }

    @Nonnull @Override
    public Map<String, Double> getUtilityBonus() {
        Map<String, Double> bonuses = new HashMap<>();
        bonuses.put("LOOT_OTHER_ISLAND", 2.0);
        bonuses.put("FIGHT_NEAREST", 0.3);
        bonuses.put("FIGHT_WEAKEST", 0.3);
        bonuses.put("FIGHT_TARGETED", 0.3);
        return bonuses;
    }

    @Override
    public void tick(@Nonnull TrainerBot bot) {
        ticksActive++;
        // Complete when well-geared or game is late
        if (bot.getDecisionEngine() != null) {
            DecisionContext ctx = bot.getDecisionEngine().getContext();
            if (ctx.equipmentScore > 0.6 || ctx.gameProgress > 0.7) {
                complete = true;
            }
        }
        if (ticksActive > 600) complete = true;
    }

    @Override
    public boolean isComplete() { return complete; }

    @Override
    public void reset() { complete = false; ticksActive = 0; }
}
