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
    private boolean eating;
    private int eatingTicksLeft;

    public FoodHandler(@Nonnull TrainerBot bot) {
        this.bot = bot;
        this.eating = false;
        this.eatingTicksLeft = 0;
    }

    /**
     * Ticks the food handler. Checks hunger and initiates eating if needed.
     */
    public void tick() {
        Player player = bot.getPlayerEntity();
        if (player == null) return;

        if (eating) {
            eatingTicksLeft--;
            if (eatingTicksLeft <= 0) {
                eating = false;
                NMSHelper.useItem(player, false);
            }
            return;
        }

        // Check hunger level (below 80% = 16 hunger points)
        if (player.getFoodLevel() < 16) {
            int foodSlot = findFood(player);
            if (foodSlot >= 0) {
                player.getInventory().setHeldItemSlot(foodSlot);
                NMSHelper.useItem(player, true);
                eating = true;
                eatingTicksLeft = 32; // 1.6 seconds eating duration
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

        return findFood(p) > 0;
    }

    /** @return true if the bot is currently eating */
    public boolean isEating() { return eating; }
}
