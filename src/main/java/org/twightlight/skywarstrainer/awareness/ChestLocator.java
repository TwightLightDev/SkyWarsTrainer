package org.twightlight.skywarstrainer.awareness;

import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.block.Chest;
import org.bukkit.inventory.Inventory;
import org.twightlight.skywarstrainer.bot.TrainerBot;
import org.twightlight.skywarstrainer.util.MathUtil;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Locates and tracks chest positions within the bot's awareness radius.
 *
 * <p>ChestLocator uses data from the MapScanner to find chest blocks,
 * then stores them with additional metadata (looted status, distance,
 * island association). The LootEngine uses this data to decide which
 * chests to visit.</p>
 *
 * <p>Chest discovery happens during map scans. The locator also provides
 * methods to find the nearest unlooted chest, all chests on the bot's
 * current island, and chests on remote islands.</p>
 */
public class ChestLocator {

    private final TrainerBot bot;

    /** All known chest locations with their metadata. */
    private final List<ChestInfo> knownChests;

    /**
     * Creates a new ChestLocator for the given bot.
     *
     * @param bot the owning trainer bot
     */
    public ChestLocator(@Nonnull TrainerBot bot) {
        this.bot = bot;
        this.knownChests = new ArrayList<>();
    }

    /**
     * Updates the chest list from the map scanner's discovered chest positions.
     * Should be called after each map scan cycle.
     *
     * @param scanner the map scanner
     */
    public void updateFromScanner(@Nonnull MapScanner scanner) {
        List<Location> scannedChests = scanner.getChestLocations();
        for (Location loc : scannedChests) {
            if (!hasChestAt(loc)) {
                ChestInfo info = new ChestInfo(loc.clone());
                knownChests.add(info);
            }
        }

        // Verify existing chests still exist (may have been broken)
        knownChests.removeIf(chest -> {
            Block block = chest.location.getBlock();
            return block.getType() != Material.CHEST && block.getType() != Material.TRAPPED_CHEST;
        });
    }

    /**
     * Marks a chest as looted by this bot.
     *
     * @param location the chest location
     */
    public void markLooted(@Nonnull Location location) {
        for (ChestInfo chest : knownChests) {
            if (isSameBlock(chest.location, location)) {
                chest.isLooted = true;
                chest.lootedByBot = true;
                return;
            }
        }
    }

    /**
     * Marks a chest as looted by an enemy (bot observed enemy looting it).
     *
     * @param location the chest location
     */
    public void markLootedByEnemy(@Nonnull Location location) {
        for (ChestInfo chest : knownChests) {
            if (isSameBlock(chest.location, location)) {
                chest.isLooted = true;
                chest.lootedByEnemy = true;
                return;
            }
        }
    }

    /**
     * Returns the nearest unlooted chest to the bot's current position.
     *
     * @return the nearest unlooted chest info, or null if none available
     */
    @Nullable
    public ChestInfo getNearestUnlootedChest() {
        Location botLoc = bot.getLocation();
        if (botLoc == null) return null;

        ChestInfo nearest = null;
        double nearestDist = Double.MAX_VALUE;

        for (ChestInfo chest : knownChests) {
            if (chest.isLooted) continue;
            if (!botLoc.getWorld().equals(chest.location.getWorld())) continue;

            double dist = MathUtil.horizontalDistance(botLoc, chest.location);
            if (dist < nearestDist) {
                nearestDist = dist;
                nearest = chest;
            }
        }
        return nearest;
    }

    /**
     * Returns all unlooted chests, sorted by distance to the bot (nearest first).
     *
     * @return sorted list of unlooted chests
     */
    @Nonnull
    public List<ChestInfo> getUnlootedChestsByDistance() {
        Location botLoc = bot.getLocation();
        if (botLoc == null) return new ArrayList<>();

        List<ChestInfo> unlooted = new ArrayList<>();
        for (ChestInfo chest : knownChests) {
            if (!chest.isLooted) {
                unlooted.add(chest);
            }
        }

        unlooted.sort(Comparator.comparingDouble(
                c -> MathUtil.horizontalDistance(botLoc, c.location)));
        return unlooted;
    }

    /**
     * Returns the number of unlooted chests known to the bot.
     *
     * @return unlooted chest count
     */
    public int getUnlootedCount() {
        int count = 0;
        for (ChestInfo chest : knownChests) {
            if (!chest.isLooted) count++;
        }
        return count;
    }

    /**
     * Returns the total number of known chests.
     *
     * @return total chest count
     */
    public int getTotalChestCount() {
        return knownChests.size();
    }

    /**
     * Returns all known chests.
     *
     * @return list of all chest info objects
     */
    @Nonnull
    public List<ChestInfo> getAllChests() {
        return knownChests;
    }

    /**
     * Checks if a chest is known to exist at the given block position.
     *
     * @param location the location to check
     * @return true if a chest is known at this position
     */
    public boolean hasChestAt(@Nonnull Location location) {
        for (ChestInfo chest : knownChests) {
            if (isSameBlock(chest.location, location)) return true;
        }
        return false;
    }

    /**
     * Checks if the chest at the given location has items remaining.
     * This performs a live check on the actual chest inventory.
     *
     * @param location the chest location
     * @return true if the chest exists and has items
     */
    public boolean chestHasItems(@Nonnull Location location) {
        Block block = location.getBlock();
        if (block.getType() != Material.CHEST && block.getType() != Material.TRAPPED_CHEST) {
            return false;
        }
        if (block.getState() instanceof Chest) {
            Inventory inv = ((Chest) block.getState()).getInventory();
            for (int i = 0; i < inv.getSize(); i++) {
                if (inv.getItem(i) != null) return true;
            }
        }
        return false;
    }

    /** Clears all known chest data. */
    public void clear() {
        knownChests.clear();
    }

    /**
     * Compares two locations at block-level precision.
     */
    private static boolean isSameBlock(@Nonnull Location a, @Nonnull Location b) {
        return a.getBlockX() == b.getBlockX()
                && a.getBlockY() == b.getBlockY()
                && a.getBlockZ() == b.getBlockZ();
    }

    /**
     * Marks ALL known chests as unlooted. Called when chests are refilled
     * in the SkyWars game, resetting the bot's chest memory.
     */
    public void markAllUnlooted() {
        for (ChestInfo chest : knownChests) {
            chest.isLooted = false;
            chest.lootedByBot = false;
            chest.lootedByEnemy = false;
            chest.itemQualityRating = -1;
            chest.failed = false;  // [FIX-A3] Reset failure flag on refill
        }
    }

    // ═══════════════════════════════════════════════════════════
    //  INNER: ChestInfo
    // ═══════════════════════════════════════════════════════════

    /** Metadata about a single chest. */
    public static class ChestInfo {
        /** The chest block's location. */
        public final Location location;

        /** Whether this chest has been looted (by anyone). */
        public boolean isLooted;

        /** Whether the bot itself looted this chest. */
        public boolean lootedByBot;

        /** Whether the bot saw an enemy loot this chest. */
        public boolean lootedByEnemy;

        /** Quality rating of items found (0-10, set after looting). */
        public int itemQualityRating;

        /**
         * Whether a loot attempt on this chest has failed (e.g., timeout due to
         * unreachable pathing). Set by LootEngine when a loot attempt times out.
         * Reset by {@link ChestLocator#markAllUnlooted()} during chest refills,
         * so previously-unreachable chests get a fresh chance after refill.
         */
        public boolean failed;  // [FIX-A3] Per-chest failure tracking

        public ChestInfo(@Nonnull Location location) {
            this.location = location;
            this.isLooted = false;
            this.lootedByBot = false;
            this.lootedByEnemy = false;
            this.itemQualityRating = -1;
            this.failed = false;
        }

        @Override
        public String toString() {
            return "ChestInfo{" + location.getBlockX() + "," + location.getBlockY()
                    + "," + location.getBlockZ()
                    + ", looted=" + isLooted + ", failed=" + failed + "}";
        }
    }

}

