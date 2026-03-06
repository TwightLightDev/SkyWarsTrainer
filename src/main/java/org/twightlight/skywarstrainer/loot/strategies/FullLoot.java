package org.twightlight.skywarstrainer.loot.strategies;

import org.bukkit.Material;
import org.bukkit.block.Chest;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.twightlight.skywarstrainer.bot.TrainerBot;
import org.twightlight.skywarstrainer.config.DifficultyConfig.DifficultyProfile;
import org.twightlight.skywarstrainer.util.RandomUtil;

import javax.annotation.Nonnull;

/**
 * Full looting: take EVERYTHING regardless of value. Preferred by COLLECTOR.
 */
public class FullLoot implements LootStrategy {

    private Chest chest;
    private boolean complete;
    private int tickCounter;
    private int nextItemTick;
    private int currentSlot;

    @Nonnull
    @Override
    public String getName() { return "FullLoot"; }

    @Override
    public void initialize(@Nonnull TrainerBot bot, @Nonnull Chest chest) {
        this.chest = chest;
        this.complete = false;
        this.tickCounter = 0;
        this.nextItemTick = 0;
        this.currentSlot = 0;
    }

    @Nonnull
    @Override
    public LootTickResult tick(@Nonnull TrainerBot bot) {
        if (complete) return LootTickResult.COMPLETE;
        Player player = bot.getPlayerEntity();
        if (player == null || chest == null) return LootTickResult.FAILED;

        tickCounter++;
        if (tickCounter < nextItemTick) return LootTickResult.IN_PROGRESS;

        Inventory chestInv = chest.getInventory();
        if (currentSlot >= chestInv.getSize()) {
            complete = true;
            return LootTickResult.COMPLETE;
        }

        DifficultyProfile diff = bot.getDifficultyProfile();
        ItemStack item = chestInv.getItem(currentSlot);

        if (item != null && item.getType() != Material.AIR && player.getInventory().firstEmpty() != -1) {
            player.getInventory().addItem(item.clone());
            chestInv.setItem(currentSlot, null);
            double baseDelay = 200.0 / diff.getLootSpeedMultiplier();
            nextItemTick = tickCounter + Math.max(2, (int)(baseDelay / 50.0)) + RandomUtil.nextInt(0, 2);
            currentSlot++;
            return LootTickResult.ITEM_TAKEN;
        }

        currentSlot++;
        return LootTickResult.IN_PROGRESS;
    }

    @Override
    public void reset() {
        chest = null; complete = false; tickCounter = 0; nextItemTick = 0; currentSlot = 0;
    }

    @Override
    public boolean isComplete() { return complete; }
}
