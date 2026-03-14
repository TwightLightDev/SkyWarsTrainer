package org.twightlight.skywarstrainer.util;

import net.minecraft.server.v1_8_R3.*;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.craftbukkit.v1_8_R3.entity.CraftPlayer;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;

import javax.annotation.Nonnull;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * NMS packet utility methods for Spigot 1.8.8 (v1_8_R3).
 *
 * <p>Provides methods for sending packets that the Bukkit API cannot handle,
 * specifically: arm swing animations, precise head rotation, and entity
 * look packets. All NMS access is centralized here and in {@link NMSHelper}
 * so version updates only require changes in these two files.</p>
 *
 * <p><strong>Thread Safety:</strong> All methods that send packets must be called
 * from the main server thread. Use {@code Bukkit.getScheduler().runTask()} if
 * calling from an async context.</p>
 */
public final class PacketUtil {

    private static final Logger LOGGER = Bukkit.getLogger();

    private PacketUtil() {
        // Static utility class — no instantiation
    }

    // ─── Packet Sending ─────────────────────────────────────────

    /**
     * Sends a packet to a specific player.
     *
     * @param player the player to receive the packet
     * @param packet the NMS packet to send
     */
    public static void sendPacket(@Nonnull Player player, @Nonnull Packet<?> packet) {
        try {
            PlayerConnection connection = ((CraftPlayer) player).getHandle().playerConnection;
            if (connection != null) {
                connection.sendPacket(packet);
            }
        } catch (Exception e) {
            LOGGER.log(Level.WARNING, "[SkyWarsTrainer] Failed to send packet to " + player.getName(), e);
        }
    }

    /**
     * Sends a packet to all online players within the given radius of a location.
     *
     * <p>Used for sending visual packets (animations, look changes) to nearby
     * observers only, reducing unnecessary network traffic.</p>
     *
     * @param center the center location
     * @param radius the maximum distance in blocks
     * @param packet the NMS packet to send
     */
    public static void sendPacketNearby(@Nonnull Location center, double radius, @Nonnull Packet<?> packet) {
        double radiusSquared = radius * radius;
        for (Player player : Bukkit.getOnlinePlayers()) {
            if (player.getWorld().equals(center.getWorld())) {
                if (player.getLocation().distanceSquared(center) <= radiusSquared) {
                    sendPacket(player, packet);
                }
            }
        }
    }

    /**
     * Sends a packet to ALL online players.
     *
     * @param packet the NMS packet to send
     */
    public static void broadcastPacket(@Nonnull Packet<?> packet) {
        for (Player player : Bukkit.getOnlinePlayers()) {
            sendPacket(player, packet);
        }
    }

    // ─── Animation Packets ──────────────────────────────────────

    /**
     * Animation IDs for {@link PacketPlayOutAnimation}.
     * These match the protocol values for Minecraft 1.8.
     */
    public static final int ANIMATION_SWING_ARM = 0;
    public static final int ANIMATION_TAKE_DAMAGE = 1;
    public static final int ANIMATION_LEAVE_BED = 2;
    public static final int ANIMATION_EAT_FOOD = 3;
    public static final int ANIMATION_CRITICAL_EFFECT = 4;
    public static final int ANIMATION_MAGIC_CRITICAL = 5;

    /**
     * Sends an arm swing animation packet for the given entity to all nearby players.
     *
     * <p>This makes the NPC appear to swing its arm as if attacking. The Bukkit API
     * does not provide a clean way to trigger arm swings on NPCs; NMS packets are
     * required.</p>
     *
     * @param entity the entity whose arm should swing
     */
    public static void playArmSwing(@Nonnull Entity entity) {
        playAnimation(entity, ANIMATION_SWING_ARM);
    }

    /**
     * Sends a damage animation (red flash) packet for the given entity.
     *
     * @param entity the entity to show taking damage
     */
    public static void playDamageAnimation(@Nonnull Entity entity) {
        playAnimation(entity, ANIMATION_TAKE_DAMAGE);
    }

    /**
     * Sends a critical hit particle effect animation for the given entity.
     *
     * @param entity the entity to show critical particles on
     */
    public static void playCriticalEffect(@Nonnull Entity entity) {
        playAnimation(entity, ANIMATION_CRITICAL_EFFECT);
    }

    /**
     * Sends an animation packet for the specified entity and animation ID.
     *
     * @param entity      the entity
     * @param animationId the animation ID (see ANIMATION_ constants)
     */
    public static void playAnimation(@Nonnull Entity entity, int animationId) {
        try {
            net.minecraft.server.v1_8_R3.Entity nmsEntity = NMSHelper.getNMSEntity(entity);
            if (nmsEntity == null) return;

            PacketPlayOutAnimation packet = new PacketPlayOutAnimation(nmsEntity, animationId);
            sendPacketNearby(entity.getLocation(), 64.0, packet);
        } catch (Exception e) {
            LOGGER.log(Level.WARNING, "[SkyWarsTrainer] Failed to play animation for entity " + entity.getEntityId(), e);
        }
    }

    // ─── Head Rotation Packets ──────────────────────────────────

    /**
     * Sends a head rotation packet for the given entity, making its head face
     * the specified yaw angle. This is separate from body rotation and creates
     * the natural-looking head movement players exhibit.
     *
     * <p>The Bukkit API's teleport method changes the full body orientation.
     * This packet only rotates the head, which looks more natural for NPCs
     * tracking a target while walking in a different direction.</p>
     *
     * @param entity the entity whose head to rotate
     * @param yaw    the yaw angle in degrees
     */
    public static void sendHeadRotation(@Nonnull Entity entity, float yaw) {
        try {
            net.minecraft.server.v1_8_R3.Entity nmsEntity = NMSHelper.getNMSEntity(entity);
            if (nmsEntity == null) return;

            /*
             * Head rotation is encoded as a byte angle: (yaw / 360) * 256.
             * This gives 256 discrete head angles, which is sufficient for
             * smooth-looking rotation.
             */
            PacketPlayOutEntityHeadRotation packet = new PacketPlayOutEntityHeadRotation(
                    nmsEntity, (byte) ((yaw * 256.0F) / 360.0F)
            );
            sendPacketNearby(entity.getLocation(), 64.0, packet);
        } catch (Exception e) {
            LOGGER.log(Level.WARNING, "[SkyWarsTrainer] Failed to send head rotation for entity " + entity.getEntityId(), e);
        }
    }

    /**
     * Sends an entity look packet (body rotation + pitch) for the given entity.
     *
     * <p>Combined with {@link #sendHeadRotation(Entity, float)}, this allows
     * independent control of body and head orientation.</p>
     *
     * @param entity the entity to rotate
     * @param yaw    the body yaw in degrees
     * @param pitch  the body pitch in degrees
     */
    public static void sendEntityLook(@Nonnull Entity entity, float yaw, float pitch) {
        try {
            byte yawByte = (byte) ((yaw * 256.0F) / 360.0F);
            byte pitchByte = (byte) ((pitch * 256.0F) / 360.0F);

            PacketPlayOutEntity.PacketPlayOutEntityLook packet =
                    new PacketPlayOutEntity.PacketPlayOutEntityLook(
                            entity.getEntityId(), yawByte, pitchByte, entity.isOnGround()
                    );
            sendPacketNearby(entity.getLocation(), 64.0, packet);
        } catch (Exception e) {
            LOGGER.log(Level.WARNING, "[SkyWarsTrainer] Failed to send entity look for entity " + entity.getEntityId(), e);
        }
    }

    /**
     * Sends both head rotation and entity look packets to fully orient an NPC
     * toward a specific location. This is the preferred method for making a bot
     * "look at" a target.
     *
     * @param entity the entity to orient
     * @param target the location to look at
     */
    public static void lookAt(@Nonnull Entity entity, @Nonnull Location target) {
        Location entityLoc = entity.getLocation();
        float yaw = MathUtil.calculateYaw(entityLoc, target);
        float pitch = MathUtil.calculatePitch(entityLoc, target);

        sendEntityLook(entity, yaw, pitch);
        sendHeadRotation(entity, yaw);
    }
}

