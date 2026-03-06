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
import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Normal looting: open chest, take useful items by priority, close.
 *
 * <p>Walk to chest → face chest → right-click to open → simulate cursor
 * movement → pick up items by priority → close chest when done.</p>
 *
 * <p>Time per item: 100-400ms depending on lootSpeedMultiplier.</p>
 */
public class NormalLoot implements LootStrategy {

    private Chest chest;
    private boolean complete;
    private int tickCounter;
    private int nextItemTick;
    private List<Integer> slotsToLoot;
    private int currentSlotIndex;

    @Nonnull
    @Override
    public String getName() { return "NormalLoot"; }

    @Override
    public void initialize(@Nonnull TrainerBot bot, @Nonnull Chest chest) {
        this.chest = chest;
        this.complete = false;
        this.tickCounter = 0;
        this.currentSlotIndex = 0;
        this.nextItemTick = 0;

        // Sort chest inventory slots by item priority (descending)
        GamePhaseTracker.GamePhase phase = getPhase(bot);
        Inventory chestInv = chest.getInventory();
        slotsToLoot = new ArrayList<>();

        List<int[]> slotPriorities = new ArrayList<>();
        for (int i = 0; i < chestInv.getSize(); i++) {
            ItemStack item = chestInv.getItem(i);
            if (item != null && item.getType() != Material.AIR) {
                double priority = LootPriorityTable.getPriority(item, phase);
                slotPriorities.add(new int[]{i, (int)(priority * 100)});
            }
        }

        // Sort by priority descending
        slotPriorities.sort((a, b) -> Integer.compare(b[1], a[1]));
        for (int[] sp : slotPriorities) {
            slotsToLoot.add(sp[0]);
        }
    }

    @Nonnull
    @Override
    public LootTickResult tick(@Nonnull TrainerBot bot) {
        if (complete) return LootTickResult.COMPLETE;
        Player player = bot.getPlayerEntity();
        if (player == null || chest == null) return LootTickResult.FAILED;

        tickCounter++;

        // Wait until next item timing
        if (tickCounter < nextItemTick) return LootTickResult.IN_PROGRESS;

        // Check if done
        if (currentSlotIndex >= slotsToLoot.size()) {
            complete = true;
            return LootTickResult.COMPLETE;
        }

        // Take the next item
        DifficultyProfile diff = bot.getDifficultyProfile();
        Inventory chestInv = chest.getInventory();
        int slot = slotsToLoot.get(currentSlotIndex);
        ItemStack item = chestInv.getItem(slot);

        if (item != null && item.getType() != Material.AIR) {
            // Check if player has inventory space
            if (player.getInventory().firstEmpty() != -1) {
                player.getInventory().addItem(item.clone());
                chestInv.setItem(slot, null);

                // Schedule next item based on loot speed
                double baseDelay = 250.0 / diff.getLootSpeedMultiplier(); // ms
                int delayTicks = Math.max(2, (int)(baseDelay / 50.0)); // Convert ms to ticks
                delayTicks += RandomUtil.nextInt(0, 3);
                nextItemTick = tickCounter + delayTicks;

                currentSlotIndex++;
                return LootTickResult.ITEM_TAKEN;
            } else {
                // Inventory full
                complete = true;
                return LootTickResult.COMPLETE;
            }
        }

        currentSlotIndex++;
        return LootTickResult.IN_PROGRESS;
    }

    @Override
    public void reset() {
        chest = null;
        complete = false;
        tickCounter = 0;
        nextItemTick = 0;
        slotsToLoot = null;
        currentSlotIndex = 0;
    }

    @Override
    public boolean isComplete() { return complete; }

    private GamePhaseTracker.GamePhase getPhase(@Nonnull TrainerBot bot) {
        GamePhaseTracker tracker = bot.getGamePhaseTracker();
        return tracker != null ? tracker.getPhase() : GamePhaseTracker.GamePhase.EARLY;
    }
}
