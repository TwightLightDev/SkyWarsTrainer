package org.twightlight.skywarstrainer.inventory;

import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.twightlight.skywarstrainer.bot.TrainerBot;
import org.twightlight.skywarstrainer.util.NMSHelper;

import javax.annotation.Nonnull;

/**
 * Manages food consumption. Eats when hunger is below 80%.
 * Prefers golden apples in combat, regular food otherwise.
 */
public class FoodHandler {

    private final TrainerBot bot;

    public FoodHandler(@Nonnull TrainerBot bot) {
        this.bot = bot;
    }

    /**
     * Ticks the food handler. Checks hunger and initiates eating if needed.
     */
    public void tick() {
        Player player = bot.getPlayerEntity();
        if (player == null) return;

        // Check hunger level (below 80% = 16 hunger points)
        if (player.getFoodLevel() < 16) {
            int foodSlot = findFood(player);
            if (foodSlot >= 0) {
                player.getInventory().setHeldItemSlot(foodSlot);
                NMSHelper.useItem(player, true);
            }
        }
    }

    private int findFood(@Nonnull Player player) {
        // Check hotbar first
        for (int i = 0; i < 9; i++) {
            ItemStack item = player.getInventory().getItem(i);
            if (item != null && item.getType().isEdible() && item.getType() != Material.GOLDEN_APPLE) {
                return i;
            }
        }
        return -1;
    }

    public boolean hasGoldenApple() {
        Player p = bot.getPlayerEntity();
        if (p == null) return false;

        for (ItemStack item : p.getInventory().getContents()) {
            if (item == null) continue;
            if (item.getType() == Material.GOLDEN_APPLE) return true;
        }
        return false;
    }

    public boolean hasFood() {
        Player p = bot.getPlayerEntity();
        if (p == null) return false;

        // [FIX B6] Was: return findFood(p) > 0;
        // This returned false when food was in slot 0 (hotbar slot 0).
        // findFood() returns -1 when not found, so the check must be >= 0.
        return findFood(p) >= 0;
    }

    /**
     * [FIX C4] Moved from EnchantmentHandler (where it didn't belong).
     * Finds a golden apple in the player's inventory.
     *
     * @param player the player
     * @return the slot index, or -1 if not found
     */
    public int findGoldenAppleSlot(@Nonnull Player player) {
        for (int i = 0; i < 36; i++) {
            ItemStack item = player.getInventory().getItem(i);
            if (item != null && item.getType() == Material.GOLDEN_APPLE) {
                return i;
            }
        }
        return -1;
    }

// In TrainerBot.java CONSUMING BT, update the reference:
// Old: int gaSlot = inventoryEngine.getEnchantmentHandler().findGoldenAppleSlot(player);
// New: int gaSlot = inventoryEngine.getFoodHandler().findGoldenAppleSlot(player);

    /** @return true if the bot is currently eating */
    public boolean isEating() {
        return NMSHelper.isEating(bot.getPlayerEntity());
    }
}
