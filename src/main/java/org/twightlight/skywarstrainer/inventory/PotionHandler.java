package org.twightlight.skywarstrainer.inventory;

import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.potion.Potion;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.potion.PotionType;
import org.twightlight.skywarstrainer.bot.TrainerBot;
import org.twightlight.skywarstrainer.config.DifficultyConfig.DifficultyProfile;
import org.twightlight.skywarstrainer.util.RandomUtil;

import javax.annotation.Nonnull;

/**
 * [FIX C5] Potion effects are now ACTUALLY APPLIED to the player.
 * Previously, the potion item was consumed from inventory but
 * player.addPotionEffect() was never called, so the bot gained nothing.
 *
 * [FIX B4] Now handles Speed, Strength, and Fire Resistance in addition
 * to Instant Health. Previously only checked for INSTANT_HEAL.
 */
public class PotionHandler {

    private final TrainerBot bot;
    private int cooldownTicks;

    public PotionHandler(@Nonnull TrainerBot bot) {
        this.bot = bot;
        this.cooldownTicks = 0;
    }

    public void tick() {
        if (cooldownTicks > 0) {
            cooldownTicks--;
            return;
        }

        Player player = bot.getPlayerEntity();
        if (player == null) return;

        DifficultyProfile diff = bot.getDifficultyProfile();
        if (!RandomUtil.chance(diff.getPotionUsageIQ() * 0.05)) return;

        double healthFrac = player.getHealth() / player.getMaxHealth();

        // Priority 1: Heal if low HP
        if (healthFrac < 0.4) {
            if (tryDrinkPotion(player, PotionType.INSTANT_HEAL)) {
                cooldownTicks = 40;
                return;
            }
        }

        // [FIX B4] Priority 2: Speed potion for chasing/fleeing
        if (!player.hasPotionEffect(PotionEffectType.SPEED)) {
            if (tryDrinkPotion(player, PotionType.SPEED)) {
                cooldownTicks = 60;
                return;
            }
        }

        // [FIX B4] Priority 3: Strength potion before/during combat
        if (!player.hasPotionEffect(PotionEffectType.INCREASE_DAMAGE)) {
            if (bot.getCombatEngine() != null && bot.getCombatEngine().isActive()) {
                if (tryDrinkPotion(player, PotionType.STRENGTH)) {
                    cooldownTicks = 60;
                    return;
                }
            }
        }

        // [FIX B4] Priority 4: Fire resistance if near lava or in fire
        if (player.getFireTicks() > 0 || (bot.getLavaDetector() != null && bot.getLavaDetector().isLavaDetected())) {
            if (!player.hasPotionEffect(PotionEffectType.FIRE_RESISTANCE)) {
                if (tryDrinkPotion(player, PotionType.FIRE_RESISTANCE)) {
                    cooldownTicks = 60;
                    return;
                }
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
                        // [FIX C5] ACTUALLY APPLY the potion effect to the player!
                        // Previously this was missing — the item was removed but
                        // the effect was never applied.
                        for (PotionEffect effect : pot.getEffects()) {
                            player.addPotionEffect(effect, true);
                        }

                        // Consume the potion
                        if (item.getAmount() > 1) {
                            item.setAmount(item.getAmount() - 1);
                        } else {
                            // Replace with empty glass bottle
                            player.getInventory().setItem(i, new ItemStack(Material.GLASS_BOTTLE, 1));
                        }
                        return true;
                    }
                } catch (Exception ignored) {}
            }
        }
        return false;
    }
}
