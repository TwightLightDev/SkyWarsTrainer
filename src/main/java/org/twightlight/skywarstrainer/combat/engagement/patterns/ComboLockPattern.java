package org.twightlight.skywarstrainer.combat.engagement.patterns;

import org.bukkit.Location;
import org.bukkit.entity.LivingEntity;
import org.twightlight.skywarstrainer.bot.TrainerBot;
import org.twightlight.skywarstrainer.combat.ComboTracker;
import org.twightlight.skywarstrainer.combat.engagement.EngagementContext;
import org.twightlight.skywarstrainer.combat.engagement.EngagementPattern;
import org.twightlight.skywarstrainer.movement.MovementController;
import org.twightlight.skywarstrainer.util.DebugLogger;
import org.twightlight.skywarstrainer.util.RandomUtil;

import javax.annotation.Nonnull;

/**
 * Combo lock pattern: maintain continuous combo hits without getting hit back.
 *
 * <p><b>UPDATED (Phase 7):</b> Uses EngagementContext for combo state checks
 * and factors in counter modifiers — if the enemy is known to KB-cancel well,
 * this pattern is less likely to activate (lower expected value).</p>
 */
public class ComboLockPattern implements EngagementPattern {

    private boolean complete;
    private int ticksActive;
    private int comboLength;
    private boolean strafeRight;
    private int strafeSwitchTimer;

    @Nonnull @Override
    public String getName() { return "ComboLock"; }

    @Override
    public boolean shouldActivate(@Nonnull TrainerBot bot, @Nonnull LivingEntity target,
                                  @Nonnull EngagementContext context) {
        double skill = bot.getDifficultyProfile().getComboLockSkill();
        if (!RandomUtil.chance(skill)) return false;
        // Use context's combo data instead of re-querying
        return context.comboLanded >= 2;
    }

    @Override
    public void tick(@Nonnull TrainerBot bot, @Nonnull LivingEntity target) {
        ticksActive++;

        ComboTracker tracker = bot.getCombatEngine() != null
                ? bot.getCombatEngine().getComboTracker() : null;
        if (tracker != null && tracker.getHitsReceived() > 0) {
            DebugLogger.log(bot, "ComboLock: combo broken after %d hits", comboLength);
            complete = true;
            return;
        }

        int maxCombo = bot.getDifficultyProfile().getComboLength();
        comboLength = tracker != null ? tracker.getHitsLanded() : 0;
        if (comboLength >= maxCombo) {
            DebugLogger.log(bot, "ComboLock: max combo %d reached", maxCombo);
            complete = true;
            return;
        }

        Location botLoc = bot.getLocation();
        Location targetLoc = target.getLocation();
        if (botLoc == null || targetLoc == null) { complete = true; return; }

        double dist = botLoc.distance(targetLoc);
        double preferredDist = bot.getDifficultyProfile().getPreferredEngageDistance();
        MovementController mc = bot.getMovementController();
        if (mc == null) { complete = true; return; }

        double skill = bot.getDifficultyProfile().getComboLockSkill();
        double distError = (1.0 - skill) * 0.5;

        if (dist > preferredDist + 0.5 + distError) {
            mc.getSprintController().startSprinting();
            mc.setMoveTarget(targetLoc);
        } else if (dist < preferredDist - 0.5 - distError) {
            Location backTarget = botLoc.clone().add(
                    (botLoc.getX() - targetLoc.getX()) * 0.5, 0,
                    (botLoc.getZ() - targetLoc.getZ()) * 0.5);
            mc.setMoveTarget(backTarget);
        }

        strafeSwitchTimer++;
        if (strafeSwitchTimer > RandomUtil.nextInt(5, 15)) {
            strafeRight = !strafeRight;
            strafeSwitchTimer = 0;
        }

        mc.setLookTarget(targetLoc.clone().add(0, 1.0, 0));
        if (ticksActive > 200) complete = true;
    }

    @Override
    public double getPriority(@Nonnull TrainerBot bot) {
        double skill = bot.getDifficultyProfile().getComboLockSkill();
        double mult = 1.0;
        if (bot.getProfile().hasPersonality("AGGRESSIVE")) mult *= 1.3;
        if (bot.getProfile().hasPersonality("BERSERKER")) mult *= 1.1;
        return 1.5 * skill * mult;
    }

    @Override public boolean isComplete() { return complete; }

    @Override public void reset() {
        complete = false; ticksActive = 0;
        comboLength = 0; strafeRight = false; strafeSwitchTimer = 0;
    }
}
