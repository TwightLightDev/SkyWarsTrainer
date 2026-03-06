package org.twightlight.skywarstrainer.inventory;

import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.twightlight.skywarstrainer.bot.TrainerBot;

import javax.annotation.Nonnull;

/**
 * Tracks the total count of building blocks in the bot's inventory.
 * Alerts the decision engine when blocks are low.
 */
public class BlockCounter {

    private final TrainerBot bot;
    private int totalBlocks;

    private static final Material[] BUILD_MATERIALS = {
            Material.COBBLESTONE, Material.STONE, Material.WOOL,
            Material.WOOD, Material.SANDSTONE, Material.DIRT,
            Material.SAND, Material.NETHERRACK, Material.ENDER_STONE,
            Material.HARD_CLAY, Material.STAINED_CLAY
    };

    public BlockCounter(@Nonnull TrainerBot bot) {
        this.bot = bot;
        this.totalBlocks = 0;
    }

    /**
     * Updates the block count from the player's inventory.
     *
     * @param player the bot's player entity
     */
    public void update(@Nonnull Player player) {
        totalBlocks = 0;
        for (ItemStack item : player.getInventory().getContents()) {
            if (item == null) continue;
            for (Material mat : BUILD_MATERIALS) {
                if (item.getType() == mat) {
                    totalBlocks += item.getAmount();
                    break;
                }
            }
        }
    }

    /** @return total building blocks available */
    public int getTotalBlocks() { return totalBlocks; }

    /** @return true if blocks are critically low (<5) */
    public boolean isCriticallyLow() { return totalBlocks < 5; }

    /** @return true if blocks are low (<10) */
    public boolean isLow() { return totalBlocks < 10; }
}
