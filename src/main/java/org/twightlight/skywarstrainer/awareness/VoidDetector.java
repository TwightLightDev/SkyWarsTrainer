package org.twightlight.skywarstrainer.awareness;

import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.twightlight.skywarstrainer.bot.TrainerBot;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * Detects void edges and unsafe positions near the bot.
 *
 * <p>In SkyWars, falling into the void (below Y=0 or into open air with no ground)
 * is one of the most common death causes. The VoidDetector identifies:
 * <ul>
 *   <li>Void edges: positions where the next step would lead to a fall</li>
 *   <li>Unsafe positions: blocks with no solid ground below</li>
 *   <li>Distance to nearest void edge (used for combat positioning)</li>
 * </ul></p>
 *
 * <p>The movement system uses this to:
 * <ul>
 *   <li>Auto-crouch near edges (CAUTIOUS personality)</li>
 *   <li>Avoid walking off platforms</li>
 *   <li>Position during combat (avoid being knocked off)</li>
 *   <li>Detect opportunities to knock enemies off (TRICKSTER, STRATEGIC)</li>
 * </ul></p>
 */
public class VoidDetector {

    private final TrainerBot bot;

    /** The minimum depth of a drop to consider it "void" (lethal). */
    private static final int VOID_DEPTH_THRESHOLD = 20;

    /** Whether the bot is currently near a void edge. */
    private boolean nearVoidEdge;

    /** Distance to the nearest void edge in blocks. */
    private double distanceToVoidEdge;

    /** Direction (as yaw) toward the nearest void edge. */
    private float voidEdgeDirection;

    /**
     * Creates a new VoidDetector for the given bot.
     *
     * @param bot the owning trainer bot
     */
    public VoidDetector(@Nonnull TrainerBot bot) {
        this.bot = bot;
        this.nearVoidEdge = false;
        this.distanceToVoidEdge = Double.MAX_VALUE;
        this.voidEdgeDirection = 0;
    }

    /**
     * Scans for void edges around the bot. Should be called periodically
     * (every 5-10 ticks for responsive edge detection).
     *
     * <p>Checks blocks in 8 directions (N, NE, E, SE, S, SW, W, NW) from
     * the bot's position, looking for the nearest point where the ground drops.</p>
     */
    public void scan() {
        Location botLoc = bot.getLocation();
        if (botLoc == null || botLoc.getWorld() == null) return;

        nearVoidEdge = false;
        distanceToVoidEdge = Double.MAX_VALUE;

        int bx = botLoc.getBlockX();
        int by = botLoc.getBlockY();
        int bz = botLoc.getBlockZ();

        // Check 8 directions plus the position directly below
        int[][] directions = {
                {1, 0}, {-1, 0}, {0, 1}, {0, -1},  // N, S, E, W
                {1, 1}, {1, -1}, {-1, 1}, {-1, -1}  // Diagonals
        };

        for (int[] dir : directions) {
            for (int dist = 1; dist <= 4; dist++) {
                int checkX = bx + dir[0] * dist;
                int checkZ = bz + dir[1] * dist;

                if (isVoidBelow(botLoc.getWorld().getBlockAt(checkX, by, checkZ).getLocation())) {
                    double edgeDist = Math.sqrt(dir[0] * dir[0] + dir[1] * dir[1]) * dist;
                    if (edgeDist < distanceToVoidEdge) {
                        distanceToVoidEdge = edgeDist;
                        nearVoidEdge = true;
                        // Calculate direction toward the void edge
                        voidEdgeDirection = (float) Math.toDegrees(
                                Math.atan2(-(checkX - bx), checkZ - bz));
                    }
                    break; // Found edge in this direction, stop scanning further
                }
            }
        }

        // Also check directly below the bot
        if (isVoidBelow(botLoc)) {
            nearVoidEdge = true;
            distanceToVoidEdge = 0;
        }
    }

    /**
     * Checks if there is void (no solid ground) below a given location.
     * Scans downward up to VOID_DEPTH_THRESHOLD blocks.
     *
     * @param location the location to check below
     * @return true if there is no solid ground within threshold depth
     */
    public boolean isVoidBelow(@Nonnull Location location) {
        if (location.getWorld() == null) return true;

        int startY = location.getBlockY() - 1;
        int minY = Math.max(0, startY - VOID_DEPTH_THRESHOLD);

        for (int y = startY; y >= minY; y--) {
            Block block = location.getWorld().getBlockAt(
                    location.getBlockX(), y, location.getBlockZ());
            if (block.getType().isSolid()) {
                return false; // Ground found
            }
        }

        // Also check if we're below Y=5 (practical void level in many SkyWars maps)
        return startY <= 5 || true; // No ground found within threshold
    }

    /**
     * Checks if a specific location is safe (has solid ground below).
     *
     * @param location the location to check
     * @return true if there is solid ground within a reasonable depth
     */
    public boolean isSafe(@Nonnull Location location) {
        return !isVoidBelow(location);
    }

    /**
     * Checks if the bot is currently standing on the edge of a platform
     * (1 block away from void in any direction).
     *
     * @return true if the bot is on a platform edge
     */
    public boolean isOnEdge() {
        return nearVoidEdge && distanceToVoidEdge <= 1.5;
    }

    /** @return true if the bot is near a void edge (within 4 blocks) */
    public boolean isNearVoidEdge() { return nearVoidEdge; }

    /** @return distance to the nearest void edge in blocks */
    public double getDistanceToVoidEdge() { return distanceToVoidEdge; }

    /** @return yaw direction toward the nearest void edge */
    public float getVoidEdgeDirection() { return voidEdgeDirection; }

    /**
     * Returns the safest direction to move (away from the nearest void edge).
     *
     * @return the yaw direction away from the void edge, or null if no edge detected
     */
    @Nullable
    public Float getSafeDirection() {
        if (!nearVoidEdge) return null;
        // Opposite of void edge direction
        return (float) ((voidEdgeDirection + 180.0) % 360.0);
    }
}

