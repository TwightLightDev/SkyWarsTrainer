package org.twightlight.skywarstrainer.combat;

import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.twightlight.skywarstrainer.bot.TrainerBot;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * Shared combat utility methods. Centralizes duplicate helper logic that was
 * copy-pasted across RangedCombatHandler, ProjectilePvPStrategy,
 * UtilityItemStrategy, and CombatEngine.
 *
 * Fixes issues A3/A4 (duplicate helpers across 3-4 classes).
 */
public final class CombatUtils {

    private CombatUtils() {} // static utility

    /**
     * Finds the nearest visible enemy player to the bot.
     *
     * @param bot the bot
     * @return the nearest enemy, or null if none visible
     */
    @Nullable
    public static LivingEntity findNearestEnemy(@Nonnull TrainerBot bot) {
        LivingEntity botEntity = bot.getLivingEntity();
        if (botEntity == null) return null;

        double nearestDist = Double.MAX_VALUE;
        LivingEntity nearest = null;
        double awarenessRadius = bot.getDifficultyProfile().getAwarenessRadius();

        for (org.bukkit.entity.Entity entity : botEntity.getNearbyEntities(
                awarenessRadius, awarenessRadius, awarenessRadius)) {
            if (entity instanceof Player && !entity.isDead()
                    && !entity.getUniqueId().equals(botEntity.getUniqueId())) {
                double dist = botEntity.getLocation().distanceSquared(entity.getLocation());
                if (dist < nearestDist) {
                    nearestDist = dist;
                    nearest = (LivingEntity) entity;
                }
            }
        }
        return nearest;
    }

    /**
     * Checks whether the target is near a void edge (2+ directions with no
     * solid ground below within 10 blocks).
     *
     * @param target the target entity
     * @return true if the target appears to be near a void edge
     */
    public static boolean isTargetNearVoid(@Nonnull LivingEntity target) {
        Location targetLoc = target.getLocation();
        World world = targetLoc.getWorld();
        if (world == null) return false;

        int[][] directions = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}};
        int voidDirections = 0;

        for (int[] dir : directions) {
            if (!hasSolidBelow(targetLoc.clone().add(dir[0] * 2, 0, dir[1] * 2))) {
                voidDirections++;
            }
        }

        return voidDirections >= 2;
    }

    /**
     * Checks whether the target appears to be on a narrow bridge (1-2 blocks wide
     * with void on both sides in at least one axis).
     *
     * @param target the target entity
     * @return true if the target appears to be on a bridge
     */
    public static boolean isTargetOnBridge(@Nonnull LivingEntity target) {
        Location targetLoc = target.getLocation();
        World world = targetLoc.getWorld();
        if (world == null) return false;

        Block below = targetLoc.clone().add(0, -1, 0).getBlock();
        if (!below.getType().isSolid()) return false;

        // Check perpendicular directions for void on both sides
        boolean voidPosX = !hasSolidBelow(targetLoc.clone().add(2, 0, 0));
        boolean voidNegX = !hasSolidBelow(targetLoc.clone().add(-2, 0, 0));
        if (voidPosX && voidNegX) return true;

        boolean voidPosZ = !hasSolidBelow(targetLoc.clone().add(0, 0, 2));
        boolean voidNegZ = !hasSolidBelow(targetLoc.clone().add(0, 0, -2));
        return voidPosZ && voidNegZ;
    }

    /**
     * Checks if there is solid ground below a location within 10 blocks.
     *
     * @param location the location to check
     * @return true if solid ground exists below
     */
    public static boolean hasSolidBelow(@Nonnull Location location) {
        for (int y = 0; y >= -10; y--) {
            Block block = location.clone().add(0, y, 0).getBlock();
            if (block.getType().isSolid()) return true;
        }
        return false;
    }

    /**
     * Checks if the player has a bow and arrows.
     */
    public static boolean hasBow(@Nonnull Player player) {
        return player.getInventory().contains(Material.BOW)
                && player.getInventory().contains(Material.ARROW);
    }

    /**
     * Checks if the player has any throwable projectile.
     */
    public static boolean hasAnyThrowable(@Nonnull Player player) {
        return player.getInventory().contains(Material.SNOW_BALL)
                || player.getInventory().contains(Material.EGG);
    }

    /**
     * Counts total projectiles (arrows + snowballs + eggs).
     */
    public static int countProjectiles(@Nonnull Player player) {
        int count = 0;
        for (ItemStack stack : player.getInventory().getContents()) {
            if (stack == null) continue;
            Material type = stack.getType();
            if (type == Material.ARROW || type == Material.SNOW_BALL || type == Material.EGG) {
                count += stack.getAmount();
            }
        }
        return count;
    }

    /**
     * Switches the player's held item to the specified material.
     */
    public static void switchToItem(@Nonnull Player player, @Nonnull Material material) {
        for (int i = 0; i < 9; i++) {
            ItemStack stack = player.getInventory().getItem(i);
            if (stack != null && stack.getType() == material) {
                player.getInventory().setHeldItemSlot(i);
                return;
            }
        }
    }

    /**
     * Switches the player's held item to their best sword.
     */
    public static void switchToSword(@Nonnull Player player) {
        Material[] swords = {Material.DIAMOND_SWORD, Material.IRON_SWORD,
                Material.STONE_SWORD, Material.GOLD_SWORD, Material.WOOD_SWORD};
        for (Material sword : swords) {
            for (int i = 0; i < 9; i++) {
                ItemStack stack = player.getInventory().getItem(i);
                if (stack != null && stack.getType() == sword) {
                    player.getInventory().setHeldItemSlot(i);
                    return;
                }
            }
        }
    }

    /**
     * Checks if the player has any sword in inventory.
     */
    public static boolean hasSword(@Nonnull Player player) {
        for (ItemStack stack : player.getInventory().getContents()) {
            if (stack != null && stack.getType().name().contains("SWORD")) {
                return true;
            }
        }
        return false;
    }
}
