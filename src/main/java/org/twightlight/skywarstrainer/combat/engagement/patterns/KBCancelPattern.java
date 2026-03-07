package org.twightlight.skywarstrainer.combat.engagement.patterns;

import org.bukkit.Location;
import org.bukkit.entity.LivingEntity;
import org.twightlight.skywarstrainer.bot.TrainerBot;
import org.twightlight.skywarstrainer.combat.engagement.EngagementContext;
import org.twightlight.skywarstrainer.combat.engagement.EngagementPattern;
import org.twightlight.skywarstrainer.movement.MovementController;
import org.twightlight.skywarstrainer.util.DebugLogger;
import org.twightlight.skywarstrainer.util.RandomUtil;

import javax.annotation.Nonnull;

/**
 * KB Cancel pattern: reduces incoming knockback by sprinting into the
 * attacker's hits. Activates when the bot has received 2+ consecutive hits.
 *
 * <p><b>UPDATED (Phase 7):</b> Uses EngagementContext for combo received checks.</p>
 */
public class KBCancelPattern implements EngagementPattern {

    private boolean complete;
    private int ticksActive;
    private static final int MAX_TICKS = 60;

    @Nonnull @Override
    public String getName() { return "KBCancel"; }

    @Override
    public boolean shouldActivate(@Nonnull TrainerBot bot, @Nonnull LivingEntity target,
                                  @Nonnull EngagementContext context) {
        double skill = bot.getDifficultyProfile().getKbCancelSkill();
        if (skill < 0.05) return false;
        // Use context — being comboed (2+ received, 0 landed since)
        return context.comboReceived >= 2;
    }

    @Override
    public void tick(@Nonnull TrainerBot bot, @Nonnull LivingEntity target) {
        ticksActive++;
        if (ticksActive > MAX_TICKS) { complete = true; return; }

        Location botLoc = bot.getLocation();
        Location targetLoc = target.getLocation();
        if (botLoc == null || targetLoc == null) { complete = true; return; }

        MovementController mc = bot.getMovementController();
        if (mc == null) { complete = true; return; }

        double skill = bot.getDifficultyProfile().getKbCancelSkill();

        // Core KB cancel mechanic: sprint TOWARD the attacker to reduce knockback
        if (RandomUtil.chance(skill)) {
            mc.getSprintController().startSprinting();
            mc.setMoveTarget(targetLoc);
            mc.setLookTarget(targetLoc.clone().add(0, 1.0, 0));

            // Jump + hit to break their combo (if skill is high enough)
            if (skill > 0.5 && ticksActive % 4 == 0) {
                mc.getJumpController().jump();
            }
        }

        // Check if combo is broken (we landed a hit)
        if (bot.getCombatEngine() != null
                && bot.getCombatEngine().getComboTracker().getHitsLanded() > 0) {
            DebugLogger.log(bot, "KBCancel: combo broken after %d ticks", ticksActive);
            complete = true;
        }
    }

    @Override
    public double getPriority(@Nonnull TrainerBot bot) {
        // High priority — survival skill
        double skill = bot.getDifficultyProfile().getKbCancelSkill();
        double mult = 1.0;
        if (bot.getProfile().hasPersonality("CLUTCH_MASTER")) mult *= 1.5;
        return 2.5 * skill * mult; // Very high priority when being comboed
    }

    @Override public boolean isComplete() { return complete; }
    @Override public void reset() { complete = false; ticksActive = 0; }
}
