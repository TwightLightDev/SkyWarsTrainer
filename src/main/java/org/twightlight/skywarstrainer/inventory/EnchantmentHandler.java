package org.twightlight.skywarstrainer.inventory;

import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.twightlight.skywarstrainer.bot.TrainerBot;

import javax.annotation.Nonnull;

/**
 * Handles enchanting table interactions. The bot walks to an enchanting table,
 * opens the interface, and applies the best available enchantment to its gear.
 *
 * <p>In Phase 5, this is a simplified implementation that tracks whether
 * enchanting is available and worthwhile. The actual enchanting mechanic
 * (opening the GUI, selecting enchant) is simulated by directly adding
 * enchantments since NPCs can't normally interact with GUIs.</p>
 */
public class EnchantmentHandler {

    private final TrainerBot bot;

    public EnchantmentHandler(@Nonnull TrainerBot bot) {
        this.bot = bot;
    }

    /**
     * Returns true if the bot should try to enchant (has levels and an unenchanted weapon/armor).
     *
     * @return true if enchanting is worthwhile
     */
    public boolean shouldEnchant() {
        Player player = bot.getPlayerEntity();
        if (player == null) return false;
        if (player.getLevel() < 1) return false;

        // Check if sword is unenchanted
        if (player.getInventory().getItem(0) != null) {
            Material mat = player.getInventory().getItem(0).getType();
            if (mat.name().endsWith("_SWORD") && player.getInventory().getItem(0).getEnchantments().isEmpty()) {
                return true;
            }
        }
        return false;
    }
}
