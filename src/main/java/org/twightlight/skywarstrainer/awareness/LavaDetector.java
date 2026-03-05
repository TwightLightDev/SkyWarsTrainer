package org.twightlight.skywarstrainer.awareness;

import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.twightlight.skywarstrainer.bot.TrainerBot;

import javax.annotation.Nonnull;

/**
 * Detects lava in the bot's vicinity for hazard avoidance.
 *
 * <p>The LavaDetector checks blocks near the bot for lava sources or flows.
 * It is used by the movement system to avoid walking into lava, by the
 * combat system to avoid being knocked into lava, and by the TRICKSTER
 * personality for area denial tactics.</p>
 *
 * <p>Detection uses the MapScanner cache when available, falling back
 * to direct world block lookups for immediate proximity checks.</p>
 */
public class LavaDetector {

    private final TrainerBot bot;

    /** Whether lava was detected near the bot on the last check. */
    private boolean lavaDetected;

    /** The nearest lava location found, or null if none. */
    private Location nearestLavaLocation;

    /** The distance to the nearest lava block. */
    private double nearestLavaDistance;

    /**
     * Creates a new LavaDetector for the given bot.
     *
     * @param bot the owning trainer bot
     */
    public LavaDetector(@Nonnull TrainerBot bot) {
        this.bot = bot;
        this.lavaDetected = false;
        this.nearestLavaLocation = null;
        this.nearestLavaDistance = Double.MAX_VALUE;
    }

    /**
     * Scans the area around the bot for lava. Should be called periodically
     * (not every tick; every 10-20 ticks is sufficient).
     *
     * <p>Checks a 7×5×7 area around the bot's feet for lava blocks.</p>
     */
    public void scan() {
        Location botLoc = bot.getLocation();
        if (botLoc == null || botLoc.getWorld() == null) return;

        lavaDetected = false;
        nearestLavaLocation = null;
        nearestLavaDistance = Double.MAX_VALUE;

        int bx = botLoc.getBlockX();
        int by = botLoc.getBlockY();
        int bz = botLoc.getBlockZ();

        // Scan a small area for lava (performance-friendly)
        for (int x = bx - 3; x <= bx + 3; x++) {
            for (int z = bz - 3; z <= bz + 3; z++) {
                for (int y = by - 2; y <= by + 2; y++) {
                    Block block = botLoc.getWorld().getBlockAt(x, y, z);
                    Material type = block.getType();
                    if (type == Material.LAVA || type == Material.STATIONARY_LAVA) {
                        lavaDetected = true;
                        Location lavaLoc = block.getLocation();
                        double dist = botLoc.distance(lavaLoc);
                        if (dist < nearestLavaDistance) {
                            nearestLavaDistance = dist;
                            nearestLavaLocation = lavaLoc;
                        }
                    }
                }
            }
        }
    }

    /**
     * Checks if a specific location is lava.
     *
     * @param location the location to check
     * @return true if the block at this location is lava
     */
    public boolean isLava(@Nonnull Location location) {
        Block block = location.getBlock();
        Material type = block.getType();
        return type == Material.LAVA || type == Material.STATIONARY_LAVA;
    }

    /**
     * Checks if there is lava within a specified radius of a location.
     *
     * @param location the center location
     * @param radius   the check radius in blocks
     * @return true if lava is found within the radius
     */
    public boolean hasLavaNear(@Nonnull Location location, int radius) {
        if (location.getWorld() == null) return false;
        int bx = location.getBlockX();
        int by = location.getBlockY();
        int bz = location.getBlockZ();

        for (int x = bx - radius; x <= bx + radius; x++) {
            for (int z = bz - radius; z <= bz + radius; z++) {
                for (int y = by - radius; y <= by + radius; y++) {
                    Block block = location.getWorld().getBlockAt(x, y, z);
                    Material type = block.getType();
                    if (type == Material.LAVA || type == Material.STATIONARY_LAVA) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    /** @return true if lava was detected near the bot on the last scan */
    public boolean isLavaDetected() { return lavaDetected; }

    /** @return the nearest lava location, or null if none found */
    public Location getNearestLavaLocation() { return nearestLavaLocation; }

    /** @return the distance to the nearest lava block, or Double.MAX_VALUE if none */
    public double getNearestLavaDistance() { return nearestLavaDistance; }
}

