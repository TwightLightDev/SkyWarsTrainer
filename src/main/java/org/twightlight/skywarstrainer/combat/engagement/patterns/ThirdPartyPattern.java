package org.twightlight.skywarstrainer.combat.engagement.patterns;

import org.bukkit.entity.LivingEntity;
import org.twightlight.skywarstrainer.bot.TrainerBot;
import org.twightlight.skywarstrainer.combat.engagement.EngagementContext;
import org.twightlight.skywarstrainer.combat.engagement.EngagementPattern;
import org.twightlight.skywarstrainer.movement.MovementController;
import org.twightlight.skywarstrainer.util.DebugLogger;

import javax.annotation.Nonnull;

/**
 * Third-party pattern: attack two players who are fighting each other.
 * Waits for one to drop below 40% HP, then rushes in.
 *
 * <p><b>UPDATED (Phase 7):</b> Uses EngagementContext for enemy fighting detection.</p>
 */
public class ThirdPartyPattern implements EngagementPattern {

    private boolean complete;
    private int ticksActive;
    private boolean rushing;
    private static final int MAX_TICKS = 200;

    @Nonnull @Override
    public String getName() { return "ThirdParty"; }

    @Override
    public boolean shouldActivate(@Nonnull TrainerBot bot, @Nonnull LivingEntity target,
                                  @Nonnull EngagementContext context) {
        double tendency = bot.getDifficultyProfile().getThirdPartyTendency();
        if (tendency < 0.1) return false;

        // Need 2+ visible enemies, AND they should be fighting each other
        if (!context.enemiesFighting) return false;
        if (context.visibleEnemyCount < 2) return false;

        // BERSERKER ignores this and just charges
        if (bot.getProfile().hasPersonality("BERSERKER")) return false;

        return true;
    }

    @Override
    public void tick(@Nonnull TrainerBot bot, @Nonnull LivingEntity target) {
        ticksActive++;
        if (ticksActive > MAX_TICKS) { complete = true; return; }

        LivingEntity botEntity = bot.getLivingEntity();
        if (botEntity == null) { complete = true; return; }

        MovementController mc = bot.getMovementController();
        if (mc == null) { complete = true; return; }

        double dist = botEntity.getLocation().distance(target.getLocation());
        double targetHealth = target.getHealth() / target.getMaxHealth();

        if (!rushing) {
            // Observing phase: wait for target to drop below 40% HP
            if (targetHealth < 0.4) {
                rushing = true;
                DebugLogger.log(bot, "ThirdParty: target at %.0f%% HP, rushing!", targetHealth * 100);
            } else {
                // Hold position at ~15 blocks
                if (dist < 12) {
                    // Too close — back off slightly
                    mc.setMoveTarget(botEntity.getLocation());
                } else if (dist > 20) {
                    // Too far — close in
                    mc.setMoveTarget(target.getLocation());
                }
                mc.setLookTarget(target.getLocation().add(0, 1.0, 0));
            }
        } else {
            // Rush phase: sprint toward target
            mc.getSprintController().startSprinting();
            mc.setMoveTarget(target.getLocation());
            mc.setLookTarget(target.getLocation().add(0, 1.0, 0));

            // Once in melee range, pattern is complete
            if (dist <= 4.0) {
                DebugLogger.log(bot, "ThirdParty: arrived in melee range");
                complete = true;
            }
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

    @Override public boolean isComplete() { return complete; }
    @Override public void reset() {
        complete = false; ticksActive = 0; rushing = false;
    }
}
