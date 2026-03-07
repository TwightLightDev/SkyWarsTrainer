package org.twightlight.skywarstrainer.movement.positional;

import org.bukkit.Location;
import org.twightlight.skywarstrainer.bot.TrainerBot;
import org.twightlight.skywarstrainer.util.RandomUtil;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.HashMap;
import java.util.Map;

/**
 * High ground control: build a 3-5 block tower and fight from elevation.
 * Provides better KB angle, harder to hit, and better vision.
 */
public class HighGroundControl implements PositionalStrategy {

    private boolean complete;
    private int ticksActive;

    @Nonnull @Override
    public String getName() { return "HighGround"; }

    @Override
    public boolean shouldActivate(@Nonnull TrainerBot bot) {
        double priority = bot.getDifficultyProfile().getHighGroundPriority();
        if (!RandomUtil.chance(priority)) return false;

        // Need blocks and an enemy nearby or approaching
        if (bot.getBridgeEngine() != null && bot.getBridgeEngine().getAvailableBlockCount() < 10) {
            return false;
        }
        if (bot.getThreatMap() != null && bot.getThreatMap().getVisibleEnemyCount() > 0) {
            return true;
        }
        return false;
    }

    @Nullable @Override
    public Location getTargetPosition(@Nonnull TrainerBot bot) {
        Location loc = bot.getLocation();
        if (loc == null) return null;
        return loc.clone().add(0, RandomUtil.nextInt(3, 5), 0);
    }

    @Nonnull @Override
    public Map<String, Double> getUtilityBonus() {
        Map<String, Double> bonuses = new HashMap<>();
        bonuses.put("CAMP_POSITION", 1.5);
        bonuses.put("BUILD_FORTIFICATION", 2.0);
        return bonuses;
    }

    @Override
    public void tick(@Nonnull TrainerBot bot) {
        ticksActive++;
        if (ticksActive > 400) complete = true; // 20 seconds max
    }

    @Override
    public boolean isComplete() { return complete; }

    @Override
    public void reset() { complete = false; ticksActive = 0; }
}
