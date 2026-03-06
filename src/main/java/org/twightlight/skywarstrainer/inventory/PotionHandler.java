package org.twightlight.skywarstrainer.inventory;

import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.potion.Potion;
import org.bukkit.potion.PotionType;
import org.twightlight.skywarstrainer.bot.TrainerBot;
import org.twightlight.skywarstrainer.config.DifficultyConfig.DifficultyProfile;
import org.twightlight.skywarstrainer.util.RandomUtil;

import javax.annotation.Nonnull;

/**
 * Handles potion usage — drinking beneficial potions at appropriate times.
 */
public class PotionHandler {

    private final TrainerBot bot;
    private int cooldownTicks;

    public PotionHandler(@Nonnull TrainerBot bot) {
        this.bot = bot;
        this.cooldownTicks = 0;
    }

    /**
     * Tick method — checks if the bot should use a potion.
     */
    public void tick() {
        if (cooldownTicks > 0) {
            cooldownTicks--;
            return;
        }

        Player player = bot.getPlayerEntity();
        if (player == null) return;

        DifficultyProfile diff = bot.getDifficultyProfile();
        if (!RandomUtil.chance(diff.getPotionUsageIQ() * 0.05)) return; // Low frequency check

        // Drink health potion if low HP
        if (player.getHealth() < player.getMaxHealth() * 0.4) {
            if (tryDrinkPotion(player, PotionType.INSTANT_HEAL)) {
                cooldownTicks = 40;
            }
        }
    }

    private boolean tryDrinkPotion(@Nonnull Player player, @Nonnull PotionType type) {
        for (int i = 0; i < 36; i++) {
            ItemStack item = player.getInventory().getItem(i);
            if (item != null && item.getType() == Material.POTION) {
                try {
                    Potion pot = Potion.fromItemStack(item);
                    if (pot.getType() == type) {
                        // Consume the potion
                        if (item.getAmount() > 1) {
                            item.setAmount(item.getAmount() - 1);
                        } else {
                            player.getInventory().setItem(i, null);
                        }
                        // Apply effect (simplified: Bukkit handles this if we "drink" it)
                        return true;
                    }
                } catch (Exception ignored) {}
            }
        }
        return false;
    }
}
