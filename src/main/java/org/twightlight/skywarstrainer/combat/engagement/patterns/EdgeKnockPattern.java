package org.twightlight.skywarstrainer.combat.engagement.patterns;

import org.bukkit.Location;
import org.bukkit.entity.LivingEntity;
import org.twightlight.skywarstrainer.awareness.VoidDetector;
import org.twightlight.skywarstrainer.bot.TrainerBot;
import org.twightlight.skywarstrainer.combat.engagement.EngagementContext;
import org.twightlight.skywarstrainer.combat.engagement.EngagementPattern;
import org.twightlight.skywarstrainer.movement.MovementController;
import org.twightlight.skywarstrainer.util.DebugLogger;
import org.twightlight.skywarstrainer.util.RandomUtil;

import javax.annotation.Nonnull;

/**
 * Edge knock pattern: position between the enemy and the void edge,
 * then sprint-attack toward the void to knock them off the map.
 *
 * <p><b>UPDATED (Phase 7):</b> Uses {@link EngagementContext} for void
 * proximity checks instead of re-scanning. Also accepts counter modifiers
 * — if the enemy is known to be a trickster (who baits near void), the
 * bot is more cautious about overcommitting.</p>
 */
public class EdgeKnockPattern implements EngagementPattern {

    private boolean complete;
    private int ticksActive;
    private static final int MAX_TICKS = 100;

    @Nonnull @Override
    public String getName() { return "EdgeKnock"; }

    @Override
    public boolean shouldActivate(@Nonnull TrainerBot bot, @Nonnull LivingEntity target,
                                  @Nonnull EngagementContext context) {
        double skill = bot.getDifficultyProfile().getEdgeKnockSkill();
        if (!RandomUtil.chance(skill)) return false;

        // Use context instead of re-scanning void detector
        if (!context.targetNearVoid) return false;

        // Don't activate if WE are also near void (too risky)
        if (context.botNearVoid && skill < 0.7) return false;

        // Counter-play awareness: if enemy is a known trickster who baits
        // near void, require higher skill to attempt this
        // (CounterModifiers integration is in the evaluatePatterns flow)
        return context.targetVoidDistance <= 5.0;
    }

    @Override
    public void tick(@Nonnull TrainerBot bot, @Nonnull LivingEntity target) {
        ticksActive++;
        if (ticksActive > MAX_TICKS) {
            complete = true;
            return;
        }

        Location botLoc = bot.getLocation();
        Location targetLoc = target.getLocation();
        if (botLoc == null || targetLoc == null) {
            complete = true;
            return;
        }

        Location voidDirection = findVoidDirection(bot, targetLoc);
        if (voidDirection == null) {
            complete = true;
            return;
        }

        MovementController mc = bot.getMovementController();
        if (mc == null) {
            complete = true;
            return;
        }

        // Position: get on the opposite side of the target from the void
        double vdx = voidDirection.getX() - targetLoc.getX();
        double vdz = voidDirection.getZ() - targetLoc.getZ();
        double len = Math.sqrt(vdx * vdx + vdz * vdz);
        if (len < 0.01) {
            complete = true;
            return;
        }
        vdx /= len;
        vdz /= len;

        Location positionTarget = targetLoc.clone().add(-vdx * 2.5, 0, -vdz * 2.5);
        mc.setMoveTarget(positionTarget);
        mc.setLookTarget(targetLoc.clone().add(0, 1.0, 0));
        mc.getSprintController().startSprinting();

        double distToTarget = botLoc.distance(targetLoc);
        DebugLogger.log(bot, "EdgeKnock: tick=%d dist=%.1f positioning", ticksActive, distToTarget);
    }

    private Location findVoidDirection(@Nonnull TrainerBot bot, @Nonnull Location targetLoc) {
        VoidDetector voidDetector = bot.getVoidDetector();
        if (voidDetector == null) return null;

        Location nearest = null;
        double nearestDist = Double.MAX_VALUE;
        for (int dx = -5; dx <= 5; dx++) {
            for (int dz = -5; dz <= 5; dz++) {
                Location check = targetLoc.clone().add(dx, 0, dz);
                if (voidDetector.isVoidBelow(check)) {
                    double dist = check.distance(targetLoc);
                    if (dist < nearestDist) {
                        nearestDist = dist;
                        nearest = check;
                    }
                }
            }
        }
        return nearest;
    }

    @Override
    public double getPriority(@Nonnull TrainerBot bot) {
        double skill = bot.getDifficultyProfile().getEdgeKnockSkill();
        double mult = 1.0;
        if (bot.getProfile().hasPersonality("TRICKSTER")) mult *= 2.0;
        if (bot.getProfile().hasPersonality("STRATEGIC")) mult *= 1.5;
        return 2.0 * skill * mult;
    }

    @Override public boolean isComplete() { return complete; }

    @Override
    public void reset() {
        complete = false;
        ticksActive = 0;
    }
}
