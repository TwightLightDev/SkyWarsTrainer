package org.twightlight.skywarstrainer.bot;

import org.twightlight.skywarstrainer.SkyWarsTrainerPlugin;
import org.twightlight.skywarstrainer.util.RandomUtil;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.List;

/**
 * Manages skin selection for trainer bots. Skins make bots look like real
 * players by giving them player-like appearances instead of the default Steve.
 *
 * <p>Skin data is applied to the Citizens NPC via the Citizens skin trait.
 * The skin is identified by a Minecraft username; Citizens handles the
 * Mojang API lookup and caching of skin texture data internally.</p>
 *
 * <p>Skins are selected from a configurable pool in config.yml. Random names
 * for bots are generated from prefix/root/suffix pools to look like real
 * player names.</p>
 */
public class BotSkin {

    /** The Minecraft username whose skin this bot uses. */
    private final String skinName;

    /** The display name of the bot (may differ from skin name). */
    private final String displayName;

    /**
     * Creates a BotSkin with the given skin and display name.
     *
     * @param skinName    the Minecraft username whose skin to use
     * @param displayName the name displayed above the NPC's head
     */
    public BotSkin(@Nonnull String skinName, @Nonnull String displayName) {
        this.skinName = skinName;
        this.displayName = displayName;
    }

    /**
     * Creates a BotSkin where the skin name and display name are the same.
     *
     * @param name the name to use for both skin and display
     */
    public BotSkin(@Nonnull String name) {
        this(name, name);
    }

    /**
     * Returns the Minecraft username whose skin texture is applied.
     *
     * @return the skin username
     */
    @Nonnull
    public String getSkinName() {
        return skinName;
    }

    /**
     * Returns the display name shown above the NPC.
     *
     * @return the display name
     */
    @Nonnull
    public String getDisplayName() {
        return displayName;
    }

    // ─── Static Factory Methods ─────────────────────────────────

    /**
     * Generates a random BotSkin by selecting a random skin from the config pool
     * and generating a random player-like display name.
     *
     * @param plugin the plugin instance for config access
     * @return a randomly generated BotSkin
     */
    @Nonnull
    public static BotSkin generateRandom(@Nonnull SkyWarsTrainerPlugin plugin) {
        String skin = getRandomSkinName(plugin);
        String name = generateRandomName(plugin);
        return new BotSkin(skin, name);
    }

    /**
     * Generates a BotSkin with a specific display name but a random skin.
     *
     * @param plugin      the plugin instance
     * @param displayName the desired display name
     * @return a BotSkin with the given name and random skin
     */
    @Nonnull
    public static BotSkin withName(@Nonnull SkyWarsTrainerPlugin plugin, @Nonnull String displayName) {
        String skin = getRandomSkinName(plugin);
        return new BotSkin(skin, displayName);
    }

    /**
     * Selects a random skin username from the config pool.
     * Falls back to "Steve" if the pool is empty.
     *
     * @param plugin the plugin instance
     * @return a random skin username
     */
    @Nonnull
    private static String getRandomSkinName(@Nonnull SkyWarsTrainerPlugin plugin) {
        if (!plugin.getConfigManager().isSkinsEnabled()) {
            return "Steve";
        }
        List<String> skins = plugin.getConfigManager().getSkinList();
        if (skins == null || skins.isEmpty()) {
            return "Steve";
        }
        return RandomUtil.randomElement(skins);
    }

    /**
     * Generates a random player-like name from the prefix/root/suffix pools
     * defined in config.yml. The name looks like a typical Minecraft username.
     *
     * <p>Format: [prefix][root][suffix], where each part is randomly selected.
     * Empty strings in the pool are valid (produces names without that part).</p>
     *
     * <p>The generated name is clamped to 16 characters (Minecraft username limit).</p>
     *
     * @param plugin the plugin instance
     * @return a random player-like name
     */
    @Nonnull
    public static String generateRandomName(@Nonnull SkyWarsTrainerPlugin plugin) {
        List<String> prefixes = plugin.getConfigManager().getNamePrefixes();
        List<String> roots = plugin.getConfigManager().getNameRoots();
        List<String> suffixes = plugin.getConfigManager().getNameSuffixes();

        String prefix = (prefixes != null && !prefixes.isEmpty()) ? RandomUtil.randomElement(prefixes) : "";
        String root = (roots != null && !roots.isEmpty()) ? RandomUtil.randomElement(roots) : "Player";
        String suffix = (suffixes != null && !suffixes.isEmpty()) ? RandomUtil.randomElement(suffixes) : "";

        String name = prefix + root + suffix;

        // Minecraft usernames are max 16 characters
        if (name.length() > 16) {
            name = name.substring(0, 16);
        }

        // Ensure the name is not empty
        if (name.isEmpty()) {
            name = "Bot" + RandomUtil.nextInt(1000, 9999);
        }

        return name;
    }

    @Override
    public String toString() {
        return "BotSkin{skin='" + skinName + "', display='" + displayName + "'}";
    }
}

