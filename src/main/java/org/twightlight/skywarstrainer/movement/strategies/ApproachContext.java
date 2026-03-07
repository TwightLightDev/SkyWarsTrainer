package org.twightlight.skywarstrainer.movement.strategies;

import org.bukkit.Location;
import org.bukkit.entity.LivingEntity;
import org.twightlight.skywarstrainer.bot.TrainerBot;

import javax.annotation.Nonnull;

/**
 * Context snapshot for approach strategy evaluation. Captures the
 * information strategies need to determine viability and priority.
 */
public class ApproachContext {

    /** Distance to target in blocks. */
    public double distanceToTarget;

    /** Height difference (positive = target is above). */
    public double heightDifference;

    /** Whether the target appears to be distracted (looting, bridging). */
    public boolean targetDistracted;

    /** Whether the target has a bow and is looking at bot. */
    public boolean targetHasBowAimed;

    /** Number of building blocks the bot has. */
    public int availableBlocks;

    /** Whether the bot has an ender pearl. */
    public boolean hasEnderPearl;

    /** Number of alive players in the game. */
    public int alivePlayerCount;

    /** Whether this is a 1v1 situation. */
    public boolean is1v1;

    /** Target's estimated HP fraction. */
    public double targetHealthEstimate;

    /** Bot's equipment score. */
    public double botEquipmentScore;

    /**
     * Populates this context from the bot and target state.
     *
     * @param bot    the bot
     * @param target the target entity
     * @return this context for chaining
     */
    @Nonnull
    public ApproachContext populate(@Nonnull TrainerBot bot, @Nonnull LivingEntity target) {
        Location botLoc = bot.getLocation();
        Location targetLoc = target.getLocation();

        if (botLoc != null && targetLoc != null) {
            distanceToTarget = botLoc.distance(targetLoc);
            heightDifference = targetLoc.getY() - botLoc.getY();
        }

        // Target distracted check: are they facing away from us?
        if (target instanceof org.bukkit.entity.Player) {
            Location eyeLoc = ((org.bukkit.entity.Player) target).getEyeLocation();
            if (botLoc != null) {
                org.bukkit.util.Vector toBot = botLoc.toVector().subtract(eyeLoc.toVector()).normalize();
                org.bukkit.util.Vector lookDir = eyeLoc.getDirection();
                double dot = toBot.dot(lookDir);
                targetDistracted = dot < 0.3; // Not looking toward bot
                targetHasBowAimed = dot > 0.8; // Looking directly at bot
            }
        }

        // Resources
        if (bot.getBridgeEngine() != null) {
            availableBlocks = bot.getBridgeEngine().getAvailableBlockCount();
        }

        // Pearl check
        if (bot.getPlayerEntity() != null) {
            hasEnderPearl = bot.getPlayerEntity().getInventory()
                    .contains(org.bukkit.Material.ENDER_PEARL);
        }

        // Player count
        try {
            alivePlayerCount = bot.getArena().getAlive();
        } catch (Exception e) {
            alivePlayerCount = 8;
        }
        is1v1 = alivePlayerCount <= 2;

        // Target health
        targetHealthEstimate = target.getHealth() / target.getMaxHealth();

        return this;
    }
}
