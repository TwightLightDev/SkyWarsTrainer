package org.twightlight.skywarstrainer.inventory;

import org.bukkit.Material;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.twightlight.skywarstrainer.bot.TrainerBot;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * Selects and places the best sword in hotbar slot 0 (configurable).
 * Ranks: diamond > iron > stone > gold > wood. Enchantments add bonus.
 */
public class SwordSelector {

    private final TrainerBot bot;
    private static final int SWORD_SLOT = 0;

    public SwordSelector(@Nonnull TrainerBot bot) {
        this.bot = bot;
    }

    /**
     * Finds the best sword in inventory and moves it to the preferred hotbar slot.
     *
     * @param player the bot's player entity
     */
    public void selectBestSword(@Nonnull Player player) {
        int bestSlot = -1;
        double bestScore = 0;

        for (int i = 0; i < 36; i++) {
            ItemStack item = player.getInventory().getItem(i);
            if (item == null) continue;
            if (!isSword(item.getType())) continue;
            double score = scoreSword(item);
            if (score > bestScore) {
                bestScore = score;
                bestSlot = i;
            }
        }

        if (bestSlot >= 0 && bestSlot != SWORD_SLOT) {
            ItemStack current = player.getInventory().getItem(SWORD_SLOT);
            ItemStack best = player.getInventory().getItem(bestSlot);
            player.getInventory().setItem(SWORD_SLOT, best);
            player.getInventory().setItem(bestSlot, current);
        }
    }

    private double scoreSword(@Nullable ItemStack item) {
        if (item == null) return 0;
        double base;
        switch (item.getType()) {
            case DIAMOND_SWORD: base = 8.0; break;
            case IRON_SWORD: base = 7.0; break;
            case STONE_SWORD: base = 6.0; break;
            case GOLD_SWORD: base = 5.0; break;
            case WOOD_SWORD: base = 5.0; break;
            default: return 0;
        }
        if (item.containsEnchantment(Enchantment.DAMAGE_ALL)) {
            base += item.getEnchantmentLevel(Enchantment.DAMAGE_ALL) * 1.25;
        }
        if (item.containsEnchantment(Enchantment.FIRE_ASPECT)) {
            base += item.getEnchantmentLevel(Enchantment.FIRE_ASPECT) * 0.5;
        }
        return base;
    }

    private boolean isSword(@Nonnull Material mat) {
        return mat == Material.DIAMOND_SWORD || mat == Material.IRON_SWORD
                || mat == Material.STONE_SWORD || mat == Material.GOLD_SWORD
                || mat == Material.WOOD_SWORD;
    }
}
