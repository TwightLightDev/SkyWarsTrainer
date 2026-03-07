package org.twightlight.skywarstrainer.movement.positional;

import org.bukkit.Location;
import org.twightlight.skywarstrainer.awareness.IslandGraph;
import org.twightlight.skywarstrainer.bot.TrainerBot;
import org.twightlight.skywarstrainer.util.RandomUtil;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.HashMap;
import java.util.Map;

/**
 * Mid control: control the center island. Loot mid chests, break bridges
 * to deny enemy access, and camp with ranged weapons.
 */
public class MidControl implements PositionalStrategy {

    private boolean complete;
    private int ticksActive;

    @Nonnull @Override
    public String getName() { return "MidControl"; }

    @Override
    public boolean shouldActivate(@Nonnull TrainerBot bot) {
        // Primarily for CAMPER and STRATEGIC personalities
        double camperMult = bot.getProfile().hasPersonality("CAMPER") ? 2.0 : 1.0;
        double strategicMult = bot.getProfile().hasPersonality("STRATEGIC") ? 1.5 : 1.0;

        // Chance based on personality and a general check
        if (!RandomUtil.chance(0.2 * camperMult * strategicMult)) return false;

        // Should be on or near mid, or have a path to mid
        IslandGraph graph = bot.getIslandGraph();
        if (graph == null) return false;
        Location botLoc = bot.getLocation();
        if (botLoc == null) return false;

        IslandGraph.Island mid = graph.getMidIsland();
        if (mid == null || mid.center == null) return false;

        double distToMid = botLoc.distance(mid.center);
        return distToMid < 30;
    }

    @Nullable @Override
    public Location getTargetPosition(@Nonnull TrainerBot bot) {
        IslandGraph graph = bot.getIslandGraph();
        if (graph != null) {
            IslandGraph.Island mid = graph.getMidIsland();
            if (mid != null) return mid.center;
        }
        return null;
    }

    @Nonnull @Override
    public Map<String, Double> getUtilityBonus() {
        Map<String, Double> bonuses = new HashMap<>();
        bonuses.put("CAMP_POSITION", 2.0);
        bonuses.put("BRIDGE_TO_MID", 2.0);
        bonuses.put("BREAK_ENEMY_BRIDGE", 1.5);
        return bonuses;
    }

    @Override
    public void tick(@Nonnull TrainerBot bot) {
        ticksActive++;
        if (ticksActive > 800) complete = true; // 40 seconds max
    }

    @Override
    public boolean isComplete() { return complete; }

    @Override
    public void reset() { complete = false; ticksActive = 0; }
}
