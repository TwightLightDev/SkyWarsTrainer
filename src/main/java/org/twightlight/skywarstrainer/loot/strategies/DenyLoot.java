package org.twightlight.skywarstrainer.loot.strategies;

import org.bukkit.Material;
import org.bukkit.block.Chest;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.twightlight.skywarstrainer.awareness.GamePhaseTracker;
import org.twightlight.skywarstrainer.bot.TrainerBot;
import org.twightlight.skywarstrainer.config.DifficultyConfig.DifficultyProfile;
import org.twightlight.skywarstrainer.loot.LootPriorityTable;
import org.twightlight.skywarstrainer.util.RandomUtil;

import javax.annotation.Nonnull;

/**
 * Deny looting: take good items, destroy/throw bad items to deny enemies.
 *
 * <p>Opens chest → takes valuable items → drops or destroys low-value items
 * by removing them from the chest (simulating throwing into void/lava).
 * Preferred by STRATEGIC, TRICKSTER.</p>
 */
public class DenyLoot implements LootStrategy {

    private Chest chest;
    private boolean complete;
    private int tickCounter;
    private int nextItemTick;
    private int currentSlot;

    @Nonnull
    @Override
    public String getName() { return "DenyLoot"; }

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
        GamePhaseTracker.GamePhase phase = getPhase(bot);
        ItemStack item = chestInv.getItem(currentSlot);

        if (item != null && item.getType() != Material.AIR) {
            double priority = LootPriorityTable.getPriority(item, phase);
            if (priority >= 4.0 && player.getInventory().firstEmpty() != -1) {
                // Good item — take it
                player.getInventory().addItem(item.clone());
            }
            // Remove from chest regardless (deny to enemies)
            chestInv.setItem(currentSlot, null);

            double baseDelay = 150.0 / diff.getLootSpeedMultiplier();
            nextItemTick = tickCounter + Math.max(1, (int)(baseDelay / 50.0)) + RandomUtil.nextInt(0, 2);
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

    private GamePhaseTracker.GamePhase getPhase(@Nonnull TrainerBot bot) {
        GamePhaseTracker tracker = bot.getGamePhaseTracker();
        return tracker != null ? tracker.getPhase() : GamePhaseTracker.GamePhase.EARLY;
    }
}
