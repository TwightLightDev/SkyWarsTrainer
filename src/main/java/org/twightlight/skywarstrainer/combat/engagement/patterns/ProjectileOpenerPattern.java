package org.twightlight.skywarstrainer.combat.engagement.patterns;

import org.bukkit.entity.LivingEntity;
import org.twightlight.skywarstrainer.bot.TrainerBot;
import org.twightlight.skywarstrainer.combat.ProjectileHandler;
import org.twightlight.skywarstrainer.combat.engagement.EngagementContext;
import org.twightlight.skywarstrainer.combat.engagement.EngagementPattern;
import org.twightlight.skywarstrainer.movement.MovementController;
import org.twightlight.skywarstrainer.util.DebugLogger;

import javax.annotation.Nonnull;

/**
 * Projectile opener: use projectiles before closing to melee range.
 *
 * <p><b>UPDATED (Phase 7):</b> Uses EngagementContext for range checks and
 * integrates with counter modifiers (if enemy is a sniper, don't open with
 * projectiles — close distance instead).</p>
 */
public class ProjectileOpenerPattern implements EngagementPattern {

    private boolean complete;
    private int ticksActive;
    private boolean switchedToMelee;
    private static final int MAX_TICKS = 120;

    @Nonnull @Override
    public String getName() { return "ProjectileOpener"; }

    @Override
    public boolean shouldActivate(@Nonnull TrainerBot bot, @Nonnull LivingEntity target,
                                  @Nonnull EngagementContext context) {
        // Need projectiles AND range > 5 blocks
        if (context.rangeToTarget < 5.0) return false;

        // Check if bot has projectile items
        ProjectileHandler handler = bot.getCombatEngine() != null
                ? bot.getCombatEngine().getProjectileHandler() : null;
        if (handler == null || !handler.hasProjectiles()) return false;

        double tendency = bot.getDifficultyProfile().getProjectileZoningTendency();
        return tendency > 0.1;
    }

    @Override
    public void tick(@Nonnull TrainerBot bot, @Nonnull LivingEntity target) {
        ticksActive++;
        if (ticksActive > MAX_TICKS) { complete = true; return; }

        LivingEntity botEntity = bot.getLivingEntity();
        if (botEntity == null) { complete = true; return; }

        double dist = botEntity.getLocation().distance(target.getLocation());
        MovementController mc = bot.getMovementController();

        if (!switchedToMelee && dist > 5.0) {
            // Ranged phase: use projectiles
            ProjectileHandler handler = bot.getCombatEngine().getProjectileHandler();
            if (handler != null && handler.hasProjectiles()) {
                handler.tick(); // ProjectileHandler manages aim + fire
            }
            // Look at target
            if (mc != null) {
                mc.setLookTarget(target.getLocation().add(0, 1.0, 0));
            }
        } else {
            // Close enough or out of projectiles — switch to melee
            switchedToMelee = true;
            if (mc != null) {
                mc.getSprintController().startSprinting();
                mc.setMoveTarget(target.getLocation());
                mc.setLookTarget(target.getLocation().add(0, 1.0, 0));
            }
            // Once in melee range, pattern is complete — hand back to normal strategies
            if (dist <= 3.5) {
                DebugLogger.log(bot, "ProjectileOpener: closed to melee range");
                complete = true;
            }
        }
    }

    @Override
    public double getPriority(@Nonnull TrainerBot bot) {
        double tendency = bot.getDifficultyProfile().getProjectileZoningTendency();
        double mult = 1.0;
        if (bot.getProfile().hasPersonality("SNIPER")) mult *= 2.5;
        if (bot.getProfile().hasPersonality("STRATEGIC")) mult *= 1.3;
        if (bot.getProfile().hasPersonality("TRICKSTER")) mult *= 1.2;
        return 1.0 * tendency * mult;
    }

    @Override public boolean isComplete() { return complete; }
    @Override public void reset() {
        complete = false; ticksActive = 0; switchedToMelee = false;
    }
}
