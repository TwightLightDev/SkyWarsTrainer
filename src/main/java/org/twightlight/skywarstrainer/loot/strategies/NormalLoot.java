// ═══════════════════════════════════════════════════════════════════
// File: src/main/java/org/twightlight/skywarstrainer/loot/strategies/NormalLoot.java
// ═══════════════════════════════════════════════════════════════════
package org.twightlight.skywarstrainer.loot.strategies;

import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.block.Chest;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.twightlight.skywarstrainer.awareness.GamePhaseTracker;
import org.twightlight.skywarstrainer.bot.TrainerBot;
import org.twightlight.skywarstrainer.config.DifficultyConfig.DifficultyProfile;
import org.twightlight.skywarstrainer.loot.LootPriorityTable;
import org.twightlight.skywarstrainer.movement.MovementController;
import org.twightlight.skywarstrainer.util.MathUtil;
import org.twightlight.skywarstrainer.util.PacketUtil;
import org.twightlight.skywarstrainer.util.RandomUtil;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.*;

/**
 * Normal looting: open chest, take useful items by priority, close chest.
 *
 * <p>The bot walks to the chest, faces it, right-clicks to open, then picks
 * up items one by one in priority order. Items are taken at a rate controlled
 * by the lootSpeedMultiplier parameter. Unneeded items are left in the chest.</p>
 *
 * <p>This is the safest and most common looting approach.</p>
 */
public class NormalLoot implements LootStrategy {

    private enum Phase {
        APPROACH,
        FACE_CHEST,
        OPEN_CHEST,
        TAKE_ITEMS,
        CLOSE_CHEST,
        DONE
    }

    private Phase phase;
    private Location chestLocation;
    private int itemsTaken;
    private int tickCounter;

    /** Items sorted by priority ready to be taken. */
    private List<ItemStack> itemsToTake;

    /** Index into itemsToTake for the next item to take. */
    private int currentItemIndex;

    /** Ticks between taking each item (simulated cursor movement). */
    private int ticksBetweenItems;

    /** Ticks since last item taken. */
    private int ticksSinceLastItem;

    @Nonnull
    @Override
    public String getName() {
        return "NormalLoot";
    }

    @Override
    public void initialize(@Nonnull TrainerBot bot, @Nonnull Location chestLocation) {
        this.chestLocation = chestLocation.clone();
        this.phase = Phase.APPROACH;
        this.itemsTaken = 0;
        this.tickCounter = 0;
        this.itemsToTake = new ArrayList<>();
        this.currentItemIndex = 0;
        this.ticksSinceLastItem = 0;

        // Calculate item-take interval based on loot speed
        DifficultyProfile diff = bot.getDifficultyProfile();
        double lootSpeed = diff.getLootSpeedMultiplier();
        // Base interval is 5-8 ticks (250-400ms), scaled by speed multiplier
        this.ticksBetweenItems = Math.max(2, (int) (6.0 / lootSpeed));
    }

    @Nonnull
    @Override
    public LootTickResult tick(@Nonnull TrainerBot bot) {
        Player player = bot.getPlayerEntity();
        if (player == null) return LootTickResult.FAILED;

        tickCounter++;

        switch (phase) {
            case APPROACH:
                return tickApproach(bot, player);
            case FACE_CHEST:
                return tickFaceChest(bot);
            case OPEN_CHEST:
                return tickOpenChest(bot, player);
            case TAKE_ITEMS:
                return tickTakeItems(bot, player);
            case CLOSE_CHEST:
                return tickCloseChest(bot, player);
            case DONE:
                return LootTickResult.COMPLETE;
            default:
                return LootTickResult.FAILED;
        }
    }

    @Nonnull
    private LootTickResult tickApproach(@Nonnull TrainerBot bot, @Nonnull Player player) {
        Location botLoc = bot.getLocation();
        if (botLoc == null) return LootTickResult.FAILED;

        // Check chest still exists
        Block block = chestLocation.getBlock();
        if (block.getType() != Material.CHEST && block.getType() != Material.TRAPPED_CHEST) {
            return LootTickResult.FAILED;
        }

        double distance = MathUtil.horizontalDistance(botLoc, chestLocation);

        if (distance < 2.5) {
            phase = Phase.FACE_CHEST;
            tickCounter = 0;
            // Stop moving
            MovementController mc = bot.getMovementController();
            if (mc != null) mc.stopAll();
            return LootTickResult.APPROACHING;
        }

        // Walk toward the chest
        MovementController mc = bot.getMovementController();
        if (mc != null) {
            mc.setMoveTarget(chestLocation.clone().add(0.5, 0, 0.5));
        }

        // Timeout
        if (tickCounter > 200) return LootTickResult.FAILED;

        return LootTickResult.APPROACHING;
    }

    @Nonnull
    private LootTickResult tickFaceChest(@Nonnull TrainerBot bot) {
        MovementController mc = bot.getMovementController();
        if (mc == null) return LootTickResult.FAILED;

        // Look at the chest
        mc.setLookTarget(chestLocation.clone().add(0.5, 0.5, 0.5));

        if (tickCounter >= 3) { // Brief pause to face
            phase = Phase.OPEN_CHEST;
            tickCounter = 0;
        }
        return LootTickResult.OPENING;
    }

    @Nonnull
    private LootTickResult tickOpenChest(@Nonnull TrainerBot bot, @Nonnull Player player) {
        Block block = chestLocation.getBlock();
        if (!(block.getState() instanceof Chest)) return LootTickResult.FAILED;

        Chest chest = (Chest) block.getState();
        Inventory chestInv = chest.getInventory();

        // Play arm swing for the "opening" animation
        PacketUtil.playArmSwing(player);

        // Build the priority-sorted list of items to take
        GamePhaseTracker gpt = bot.getGamePhaseTracker();
        GamePhaseTracker.GamePhase gamePhase = gpt != null
                ? gpt.getPhase() : GamePhaseTracker.GamePhase.EARLY;

        itemsToTake.clear();
        for (int i = 0; i < chestInv.getSize(); i++) {
            ItemStack item = chestInv.getItem(i);
            if (item != null && item.getType() != Material.AIR) {
                itemsToTake.add(item.clone());
            }
        }

        // Sort by priority (highest first)
        final GamePhaseTracker.GamePhase finalPhase = gamePhase;
        itemsToTake.sort((a, b) -> Double.compare(
                LootPriorityTable.getPriority(b, finalPhase),
                LootPriorityTable.getPriority(a, finalPhase)));

        // Filter by loot decision quality — lower quality means more junk taken
        DifficultyProfile diff = bot.getDifficultyProfile();
        double quality = diff.getLootDecisionQuality();
        if (quality < 0.8) {
            // Poor quality: keep some junk items that should have been filtered
            // Good quality: accurately filter low-priority items
        }

        currentItemIndex = 0;
        ticksSinceLastItem = 0;
        phase = Phase.TAKE_ITEMS;
        tickCounter = 0;

        return LootTickResult.OPENING;
    }

    @Nonnull
    private LootTickResult tickTakeItems(@Nonnull TrainerBot bot, @Nonnull Player player) {
        if (currentItemIndex >= itemsToTake.size()) {
            phase = Phase.CLOSE_CHEST;
            tickCounter = 0;
            return LootTickResult.LOOTING;
        }

        ticksSinceLastItem++;

        // Wait for the item-take interval (simulates cursor movement)
        if (ticksSinceLastItem < ticksBetweenItems) {
            return LootTickResult.LOOTING;
        }

        ticksSinceLastItem = 0;

        // Take the next item
        ItemStack itemToTake = itemsToTake.get(currentItemIndex);

        // Check if we should take this item (loot decision quality)
        DifficultyProfile diff = bot.getDifficultyProfile();
        double quality = diff.getLootDecisionQuality();
        GamePhaseTracker gpt = bot.getGamePhaseTracker();
        GamePhaseTracker.GamePhase gamePhase = gpt != null
                ? gpt.getPhase() : GamePhaseTracker.GamePhase.EARLY;

        double priority = LootPriorityTable.getPriority(itemToTake, gamePhase);
        boolean shouldTake;

        if (quality >= 0.9) {
            // Expert: take items with priority >= 3.0
            shouldTake = priority >= 3.0;
        } else if (quality >= 0.6) {
            // Medium: take items with priority >= 2.0
            shouldTake = priority >= 2.0;
        } else {
            // Low quality: sometimes take junk, sometimes miss good items
            shouldTake = priority >= 1.5 || RandomUtil.chance(0.3);
            // Occasionally miss good items
            if (priority >= 5.0 && RandomUtil.chance(1.0 - quality)) {
                shouldTake = false; // Missed a good item!
            }
        }

        if (shouldTake && hasInventorySpace(player)) {
            // Actually transfer the item
            Block block = chestLocation.getBlock();
            if (block.getState() instanceof Chest) {
                Chest chest = (Chest) block.getState();
                Inventory chestInv = chest.getInventory();

                // Find and remove the item from the chest
                for (int i = 0; i < chestInv.getSize(); i++) {
                    ItemStack inChest = chestInv.getItem(i);
                    if (inChest != null && inChest.isSimilar(itemToTake)) {
                        chestInv.setItem(i, null);
                        player.getInventory().addItem(inChest);
                        itemsTaken++;
                        break;
                    }
                }
            }
        }

        currentItemIndex++;

        // Timeout for looting
        if (tickCounter > 200) {
            phase = Phase.CLOSE_CHEST;
            tickCounter = 0;
        }

        return LootTickResult.LOOTING;
    }

    @Nonnull
    private LootTickResult tickCloseChest(@Nonnull TrainerBot bot, @Nonnull Player player) {
        // "Close" the chest (in practice we just stop interacting)
        // Clear the look target
        MovementController mc = bot.getMovementController();
        if (mc != null) {
            mc.setLookTarget(null);
            mc.setFrozen(false);
        }

        phase = Phase.DONE;
        return LootTickResult.COMPLETE;
    }

    private boolean hasInventorySpace(@Nonnull Player player) {
        return player.getInventory().firstEmpty() != -1;
    }

    @Override
    public void reset() {
        phase = Phase.APPROACH;
        chestLocation = null;
        itemsTaken = 0;
        tickCounter = 0;
        itemsToTake = new ArrayList<>();
        currentItemIndex = 0;
    }

    @Override
    public int getItemsTaken() {
        return itemsTaken;
    }
}
