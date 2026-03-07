package org.twightlight.skywarstrainer.movement.positional;

import org.bukkit.Location;
import org.twightlight.skywarstrainer.awareness.ThreatMap;
import org.twightlight.skywarstrainer.bot.TrainerBot;
import org.twightlight.skywarstrainer.util.RandomUtil;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Third-party positioning: position near two fighting enemies and wait
 * for one to die or get low HP, then rush in to clean up.
 */
public class ThirdPartyPositioning implements PositionalStrategy {

    private boolean complete;
    private int ticksActive;
    private Location fightLocation;

    @Nonnull @Override
    public String getName() { return "ThirdPartyPos"; }

    @Override
    public boolean shouldActivate(@Nonnull TrainerBot bot) {
        double tendency = bot.getDifficultyProfile().getThirdPartyTendency();
        if (!RandomUtil.chance(tendency)) return false;

        // Detect two enemies fighting
        ThreatMap tm = bot.getThreatMap();
        if (tm == null) return false;

        List<ThreatMap.ThreatEntry> threats = tm.getVisibleThreats();
        if (threats.size() < 2) return false;

        for (int i = 0; i < threats.size(); i++) {
            for (int j = i + 1; j < threats.size(); j++) {
                ThreatMap.ThreatEntry a = threats.get(i);
                ThreatMap.ThreatEntry b = threats.get(j);
                if (a.currentPosition != null && b.currentPosition != null
                        && a.currentPosition.distance(b.currentPosition) < 5
                        && a.getHorizontalSpeed() > 0.05
                        && b.getHorizontalSpeed() > 0.05) {
                    fightLocation = a.currentPosition.clone()
                            .add(b.currentPosition).multiply(0.5); // Midpoint
                    return true;
                }
            }
        }
        return false;
    }

    @Nullable @Override
    public Location getTargetPosition(@Nonnull TrainerBot bot) {
        if (fightLocation == null) return null;
        // Position 15-20 blocks from the fight
        Location botLoc = bot.getLocation();
        if (botLoc == null) return fightLocation;

        double dx = fightLocation.getX() - botLoc.getX();
        double dz = fightLocation.getZ() - botLoc.getZ();
        double len = Math.sqrt(dx * dx + dz * dz);
        if (len < 1) return fightLocation;

        // Target position is 18 blocks from the fight toward the bot
        return fightLocation.clone().add(-dx / len * 18, 0, -dz / len * 18);
    }

    @Nonnull @Override
    public Map<String, Double> getUtilityBonus() {
        Map<String, Double> bonuses = new HashMap<>();
        bonuses.put("HUNT_PLAYER", 1.5);
        bonuses.put("CAMP_POSITION", 0.5);
        return bonuses;
    }

    @Override
    public void tick(@Nonnull TrainerBot bot) {
        ticksActive++;
        if (ticksActive > 300) complete = true;
    }

    @Override
    public boolean isComplete() { return complete; }

    @Override
    public void reset() {
        complete = false;
        ticksActive = 0;
        fightLocation = null;
    }
}
