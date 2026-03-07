package org.twightlight.skywarstrainer.movement.strategies.approaches;

import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.twightlight.skywarstrainer.bot.TrainerBot;
import org.twightlight.skywarstrainer.config.DifficultyConfig.DifficultyProfile;
import org.twightlight.skywarstrainer.movement.strategies.*;
import org.twightlight.skywarstrainer.util.DebugLogger;
import org.twightlight.skywarstrainer.util.MathUtil;

import javax.annotation.Nonnull;
import java.util.Collections;

/**
 * If bot has an ender pearl, throw it to land on enemy's island.
 * Fastest approach by far, but uses a valuable pearl.
 */
public class PearlApproach implements ApproachStrategy {

    private boolean pearlThrown;
    private int waitTicks;
    private static final int MAX_WAIT = 60; // 3 seconds for pearl to land

    @Nonnull
    @Override
    public String getName() { return "PearlApproach"; }

    @Override
    public boolean shouldUse(@Nonnull TrainerBot bot, @Nonnull LivingEntity target,
                             @Nonnull ApproachContext context) {
        DifficultyProfile diff = bot.getDifficultyProfile();
        // Must have a pearl, target within ~40 blocks, sufficient pearl IQ
        if (!context.hasEnderPearl) return false;
        if (context.distanceToTarget > 40 || context.distanceToTarget < 5) return false;
        if (diff.getPearlUsageIQ() < 0.1) return false;
        // CAUTIOUS doesn't use pearls for approach (saves for escape)
        if (bot.getProfile().hasPersonality("CAUTIOUS")) return false;
        return true;
    }

    @Nonnull
    @Override
    public ApproachPath calculateApproachPath(@Nonnull TrainerBot bot, @Nonnull LivingEntity target) {
        Location botLoc = bot.getLocation();
        Location targetLoc = target.getLocation();
        return new ApproachPath(
                ApproachPath.ApproachType.PEARL,
                Collections.singletonList(targetLoc),
                false, botLoc, targetLoc,
                1.5, // Pearl travel time
                0.2 // Low risk (instant arrival)
        );
    }

    @Override
    @Nonnull
    public ApproachTickResult tick(@Nonnull TrainerBot bot) {
        if (!pearlThrown) {
            Player player = bot.getPlayerEntity();
            if (player == null) return ApproachTickResult.FAILED;

            // Find ender pearl in inventory
            int pearlSlot = -1;
            for (int i = 0; i < player.getInventory().getSize(); i++) {
                ItemStack item = player.getInventory().getItem(i);
                if (item != null && item.getType() == Material.ENDER_PEARL) {
                    pearlSlot = i;
                    break;
                }
            }
            if (pearlSlot < 0) return ApproachTickResult.FAILED;

            // Switch to pearl, aim at target, throw
            LivingEntity entity = bot.getLivingEntity();
            if (entity == null) return ApproachTickResult.FAILED;

            // Aim toward target (handled by looking at them)
            // The actual pearl throwing uses CombatEngine's ProjectileHandler
            if (bot.getCombatEngine() != null) {
                // Set item in hand to pearl
                player.getInventory().setHeldItemSlot(pearlSlot);

                // Simulate throwing the pearl
                Location targetLoc = bot.getCombatEngine().getCurrentTarget() != null
                        ? bot.getCombatEngine().getCurrentTarget().getLocation()
                        : entity.getLocation().add(entity.getLocation().getDirection().multiply(20));

                // Look at target with upward angle for arc
                if (bot.getMovementController() != null) {
                    float yaw = MathUtil.calculateYaw(entity.getLocation(), targetLoc);
                    float pitch = MathUtil.calculatePitch(entity.getEyeLocation(), targetLoc);
                    // Add slight upward angle for pearl arc
                    pitch -= 10;
                    bot.getMovementController().setCurrentYaw(yaw);
                    bot.getMovementController().setCurrentPitch(pitch);
                }

                // Throw pearl (right-click simulation)
                player.launchProjectile(org.bukkit.entity.EnderPearl.class);
                pearlThrown = true;
                waitTicks = 0;

                DebugLogger.log(bot, "PearlApproach: pearl thrown toward target");
            }
            return ApproachTickResult.IN_PROGRESS;
        }

        // Wait for pearl to land (teleport happens automatically)
        waitTicks++;
        if (waitTicks > MAX_WAIT) {
            return ApproachTickResult.ARRIVED; // Assume we arrived
        }

        // Check if we're near the target now
        Location botLoc = bot.getLocation();
        LivingEntity entity = bot.getLivingEntity();
        if (botLoc != null && entity != null) {
            // If pearl teleported us, we should be close to target
            for (org.bukkit.entity.Entity nearby : entity.getNearbyEntities(6, 6, 6)) {
                if (nearby instanceof LivingEntity) {
                    return ApproachTickResult.ARRIVED;
                }
            }
        }

        return ApproachTickResult.IN_PROGRESS;
    }

    @Override
    public double getPriority(@Nonnull TrainerBot bot) {
        double base = 0.7; // High priority when available
        if (bot.getProfile().hasPersonality("RUSHER")) base *= 2.0;
        if (bot.getProfile().hasPersonality("AGGRESSIVE")) base *= 1.5;
        if (bot.getProfile().hasPersonality("TRICKSTER")) base *= 1.3;
        // Scale by pearl IQ
        base *= bot.getDifficultyProfile().getPearlUsageIQ();
        return base;
    }

    @Override
    public void reset() {
        pearlThrown = false;
        waitTicks = 0;
    }
}
