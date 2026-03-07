package org.twightlight.skywarstrainer.combat.engagement;

import org.bukkit.entity.LivingEntity;
import org.twightlight.skywarstrainer.awareness.ThreatMap;
import org.twightlight.skywarstrainer.bot.TrainerBot;
import org.twightlight.skywarstrainer.movement.MovementController;
import org.twightlight.skywarstrainer.util.DebugLogger;
import org.twightlight.skywarstrainer.util.RandomUtil;

import javax.annotation.Nonnull;
import java.util.List;

/**
 * Third-party pattern: wait for two enemies fighting each other, then
 * rush in when one drops low HP. Target the weaker one for the easy kill,
 * then re-engage the surviving (weakened) fighter.
 *
 * <p>Activates when the bot sees two enemies within 10 blocks of each other
 * and both appear to be fighting (both took damage recently).</p>
 */
public class ThirdPartyPattern implements EngagementPattern {

    private boolean complete;
    private int ticksActive;
    private boolean rushing;

    @Nonnull @Override
    public String getName() { return "ThirdParty"; }

    @Override
    public boolean shouldActivate(@Nonnull TrainerBot bot, @Nonnull LivingEntity target) {
        double tendency = bot.getDifficultyProfile().getThirdPartyTendency();
        if (!RandomUtil.chance(tendency)) return false;

        // BERSERKER ignores this pattern and just charges in
        if (bot.getProfile().hasPersonality("BERSERKER")) return false;

        ThreatMap tm = bot.getThreatMap();
        if (tm == null) return false;

        List<ThreatMap.ThreatEntry> threats = tm.getVisibleThreats();
        if (threats.size() < 2) return false;

        // Check if two enemies are near each other and both fighting
        for (int i = 0; i < threats.size(); i++) {
            for (int j = i + 1; j < threats.size(); j++) {
                ThreatMap.ThreatEntry a = threats.get(i);
                ThreatMap.ThreatEntry b = threats.get(j);
                if (a.currentPosition == null || b.currentPosition == null) continue;

                double distBetween = a.currentPosition.distance(b.currentPosition);
                if (distBetween < 10) {
                    // Both moving (fighting indicator)
                    if (a.getHorizontalSpeed() > 0.05 && b.getHorizontalSpeed() > 0.05) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    @Override
    public void tick(@Nonnull TrainerBot bot, @Nonnull LivingEntity target) {
        ticksActive++;

        LivingEntity botEntity = bot.getLivingEntity();
        if (botEntity == null) {
            complete = true;
            return;
        }

        ThreatMap tm = bot.getThreatMap();
        if (tm == null) {
            complete = true;
            return;
        }

        if (!rushing) {
            // Wait phase: observe the fight
            // Position at a safe distance (15-20 blocks)
            MovementController mc = bot.getMovementController();
            if (mc != null) {
                mc.setLookTarget(target.getLocation().add(0, 1.0, 0));
                // Don't move too close yet
                double dist = botEntity.getLocation().distance(target.getLocation());
                if (dist > 20) {
                    mc.setMoveTarget(target.getLocation());
                    mc.getSprintController().startSprinting();
                } else {
                    mc.setMoveTarget(null); // Hold position
                }
            }

            // Check if one has dropped below 40% HP — we don't have direct access
            // to other player HP in 1.8, but we can use heuristics or check if one died
            // For now: rush after observing for a set time
            int patienceTicks = bot.getDifficultyProfile().getApproachPatienceTicks();
            if (ticksActive > patienceTicks / 2) {
                rushing = true;
                DebugLogger.log(bot, "ThirdParty: rushing in after %d ticks of observation",
                        ticksActive);
            }
        } else {
            // Rush phase: sprint in and engage the weaker target
            MovementController mc = bot.getMovementController();
            if (mc != null) {
                mc.setMoveTarget(target.getLocation());
                mc.getSprintController().startSprinting();
                mc.setLookTarget(target.getLocation().add(0, 1.0, 0));
            }

            double dist = botEntity.getLocation().distance(target.getLocation());
            if (dist < 4.0) {
                // In melee range — pattern done, normal combat takes over
                complete = true;
            }
        }

        if (ticksActive > 300) {
            complete = true;
        }
    }

    @Override
    public double getPriority(@Nonnull TrainerBot bot) {
        double tendency = bot.getDifficultyProfile().getThirdPartyTendency();
        double mult = 1.0;
        if (bot.getProfile().hasPersonality("STRATEGIC")) mult *= 2.0;
        if (bot.getProfile().hasPersonality("CAUTIOUS")) mult *= 1.5;
        if (bot.getProfile().hasPersonality("SNIPER")) mult *= 1.3;
        return 1.8 * tendency * mult;
    }

    @Override
    public boolean isComplete() { return complete; }

    @Override
    public void reset() {
        complete = false;
        ticksActive = 0;
        rushing = false;
    }
}
