package org.twightlight.skywarstrainer.combat.engagement;

import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.twightlight.skywarstrainer.bot.TrainerBot;
import org.twightlight.skywarstrainer.combat.ProjectileHandler;
import org.twightlight.skywarstrainer.movement.MovementController;
import org.twightlight.skywarstrainer.util.DebugLogger;
import org.twightlight.skywarstrainer.util.MathUtil;

import javax.annotation.Nonnull;

/**
 * Projectile opener pattern: use ranged weapons before closing to melee.
 * Shoots bow, throws snowballs/eggs at range, then switches to sword
 * and sprints in for melee follow-up after landing a hit.
 *
 * <p>Activates when the bot has projectiles AND is >5 blocks from target.</p>
 */
public class ProjectileOpenerPattern implements EngagementPattern {

    private boolean complete;
    private int ticksActive;
    private boolean projectileLanded;
    private boolean closingIn;

    @Nonnull @Override
    public String getName() { return "ProjectileOpener"; }

    @Override
    public boolean shouldActivate(@Nonnull TrainerBot bot, @Nonnull LivingEntity target) {
        if (bot.getCombatEngine() == null) return false;
        LivingEntity botEntity = bot.getLivingEntity();
        if (botEntity == null) return false;

        double range = botEntity.getLocation().distance(target.getLocation());
        if (range < 5 || range > 30) return false;

        // Check if bot has projectiles
        Player player = bot.getPlayerEntity();
        if (player == null) return false;

        boolean hasRanged = false;
        for (ItemStack item : player.getInventory().getContents()) {
            if (item == null) continue;
            String name = item.getType().name();
            if (name.equals("BOW") || name.equals("SNOW_BALL") || name.equals("EGG")) {
                hasRanged = true;
                break;
            }
        }

        return hasRanged;
    }

    @Override
    public void tick(@Nonnull TrainerBot bot, @Nonnull LivingEntity target) {
        ticksActive++;

        LivingEntity botEntity = bot.getLivingEntity();
        if (botEntity == null) {
            complete = true;
            return;
        }

        double range = botEntity.getLocation().distance(target.getLocation());

        if (closingIn || range < 4.0) {
            // Close enough — switch to melee, pattern complete
            DebugLogger.log(bot, "ProjectileOpener: closing in, range=%.1f", range);
            complete = true;
            return;
        }

        // Use projectile handler for ranged attacks
        ProjectileHandler handler = bot.getCombatEngine().getProjectileHandler();
        if (handler != null) {
            // The projectile handler manages aim, timing, and firing
            // We just need to ensure the bot is looking at the target
            MovementController mc = bot.getMovementController();
            if (mc != null) {
                mc.setLookTarget(target.getLocation().add(0, 1.0, 0));
            }
        }

        // After firing (or after enough ticks), close in
        if (ticksActive > 40) {
            closingIn = true;
            MovementController mc = bot.getMovementController();
            if (mc != null) {
                mc.setMoveTarget(target.getLocation());
                mc.getSprintController().startSprinting();
            }
        }

        if (ticksActive > 100) {
            complete = true;
        }
    }

    @Override
    public double getPriority(@Nonnull TrainerBot bot) {
        double mult = 1.0;
        if (bot.getProfile().hasPersonality("SNIPER")) mult *= 2.5;
        if (bot.getProfile().hasPersonality("STRATEGIC")) mult *= 1.3;
        if (bot.getProfile().hasPersonality("TRICKSTER")) mult *= 1.2;
        return 1.3 * mult;
    }

    @Override
    public boolean isComplete() { return complete; }

    @Override
    public void reset() {
        complete = false;
        ticksActive = 0;
        projectileLanded = false;
        closingIn = false;
    }
}
