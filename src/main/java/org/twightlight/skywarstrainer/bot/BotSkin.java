package org.twightlight.skywarstrainer.bot;

import net.citizensnpcs.api.npc.NPC;
import org.bukkit.configuration.ConfigurationSection;
import org.twightlight.skywarstrainer.SkyWarsTrainer;
import org.twightlight.skywarstrainer.config.DifficultyConfig.Difficulty;
import org.twightlight.skywarstrainer.util.RandomUtil;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Manages skin selection and application for trainer bots.
 *
 * <p>Supports three skin source modes:</p>
 * <ul>
 *   <li><b>USERNAME</b> — Citizens resolves texture via Mojang API by username</li>
 *   <li><b>TEXTURE</b> — Raw base64 texture+signature supplied directly (works offline)</li>
 *   <li><b>MIXED</b> — Each entry is auto-detected; both types coexist in pools</li>
 * </ul>
 *
 * <p>Skin pools are resolved in priority order:
 * personality-pool > difficulty-pool > global-pool.</p>
 */
public class BotSkin {

    /** The Minecraft username whose skin this bot uses (for USERNAME mode). */
    private final String skinName;

    /** The display name of the bot (above NPC head). */
    private final String displayName;

    /** Raw texture base64 value (for TEXTURE mode, null if USERNAME mode). */
    private final String textureValue;

    /** Raw texture signature (for TEXTURE mode, null if USERNAME mode). */
    private final String textureSignature;

    // ── Constructors ──

    /** USERNAME mode constructor. */
    public BotSkin(@Nonnull String skinName, @Nonnull String displayName) {
        this(skinName, displayName, null, null);
    }

    /** Single-name constructor (skin name = display name). */
    public BotSkin(@Nonnull String name) {
        this(name, name, null, null);
    }

    /** Full constructor supporting both modes. */
    public BotSkin(@Nonnull String skinName, @Nonnull String displayName,
                   @Nullable String textureValue, @Nullable String textureSignature) {
        this.skinName = skinName;
        this.displayName = displayName;
        this.textureValue = textureValue;
        this.textureSignature = textureSignature;
    }

    // ── Getters ──

    @Nonnull public String getSkinName() { return skinName; }
    @Nonnull public String getDisplayName() { return displayName; }
    @Nullable public String getTextureValue() { return textureValue; }
    @Nullable public String getTextureSignature() { return textureSignature; }

    /** Returns true if this skin uses raw texture data instead of a username lookup. */
    public boolean isTextureBased() {
        return textureValue != null && textureSignature != null
                && !textureValue.isEmpty() && !textureSignature.isEmpty();
    }

    /**
     * Applies this skin to a Citizens NPC. Handles both username-based and
     * raw-texture-based skins transparently.
     *
     * @param npc the Citizens NPC to apply the skin to
     */
    public void applyToNPC(@Nonnull NPC npc) {
        if (isTextureBased()) {
            // Raw texture mode — set texture data directly, no Mojang API call
            npc.data().set(NPC.Metadata.PLAYER_SKIN_TEXTURE_PROPERTIES, textureValue);
            npc.data().set("player-skin-textures", textureValue);
            npc.data().set("player-skin-signature", textureSignature);
            npc.data().set(NPC.Metadata.PLAYER_SKIN_USE_LATEST, false);
        } else {
            // Username mode — Citizens resolves via Mojang API
            npc.data().set(NPC.Metadata.PLAYER_SKIN_UUID, skinName);
            npc.data().set("player-skin-name", skinName);
        }
    }

    // ── Static Factory Methods ──

    /**
     * Generates a random BotSkin by resolving the best skin pool for the given
     * difficulty and personalities, then generating a random display name.
     *
     * @param plugin        the plugin instance
     * @param difficulty    the bot's difficulty (for pool resolution)
     * @param personalities the bot's personality names (for pool resolution)
     * @return a randomly generated BotSkin
     */
    @Nonnull
    public static BotSkin generateRandom(@Nonnull SkyWarsTrainer plugin,
                                         @Nullable Difficulty difficulty,
                                         @Nullable List<String> personalities) {
        SkinEntry skinEntry = resolveSkinEntry(plugin, difficulty, personalities);
        String name = generateRandomName(plugin);
        if (skinEntry.textureValue != null) {
            return new BotSkin(skinEntry.name, name, skinEntry.textureValue, skinEntry.signature);
        }
        return new BotSkin(skinEntry.name, name);
    }

    /** Backwards-compatible overload. */
    @Nonnull
    public static BotSkin generateRandom(@Nonnull SkyWarsTrainer plugin) {
        return generateRandom(plugin, null, null);
    }

    /** Creates a BotSkin with a specific display name but resolved skin. */
    @Nonnull
    public static BotSkin withName(@Nonnull SkyWarsTrainer plugin, @Nonnull String displayName) {
        return withName(plugin, displayName, null, null);
    }

    @Nonnull
    public static BotSkin withName(@Nonnull SkyWarsTrainer plugin,
                                   @Nonnull String displayName,
                                   @Nullable Difficulty difficulty,
                                   @Nullable List<String> personalities) {
        SkinEntry skinEntry = resolveSkinEntry(plugin, difficulty, personalities);
        if (skinEntry.textureValue != null) {
            return new BotSkin(skinEntry.name, displayName, skinEntry.textureValue, skinEntry.signature);
        }
        return new BotSkin(skinEntry.name, displayName);
    }

    // ── Skin Pool Resolution ──

    /**
     * Resolves a skin entry from the config pools in priority order:
     * personality-pool > difficulty-pool > global-pool > "Steve" fallback.
     */
    @Nonnull
    private static SkinEntry resolveSkinEntry(@Nonnull SkyWarsTrainer plugin,
                                              @Nullable Difficulty difficulty,
                                              @Nullable List<String> personalities) {
        if (!plugin.getConfigManager().isSkinsEnabled()) {
            return new SkinEntry("Steve", null, null);
        }

        ConfigurationSection skinsSection = plugin.getConfigManager().getMainConfig()
                .getConfigurationSection("skins");
        if (skinsSection == null) return new SkinEntry("Steve", null, null);

        // 1. Try personality-specific pool
        if (personalities != null && !personalities.isEmpty()) {
            ConfigurationSection persSection = skinsSection.getConfigurationSection("personality-pools");
            if (persSection != null) {
                for (String pers : personalities) {
                    SkinEntry entry = pickFromPool(persSection.getConfigurationSection(pers.toUpperCase()));
                    if (entry != null) return entry;
                }
            }
        }

        // 2. Try difficulty-specific pool
        if (difficulty != null) {
            ConfigurationSection diffSection = skinsSection.getConfigurationSection(
                    "difficulty-pools." + difficulty.name());
            SkinEntry entry = pickFromPool(diffSection);
            if (entry != null) return entry;
        }

        // 3. Fall back to global pool
        ConfigurationSection globalPool = skinsSection.getConfigurationSection("global-pool");
        SkinEntry entry = pickFromPool(globalPool);
        if (entry != null) return entry;

        // 4. Legacy fallback: read old flat skin-list
        List<String> legacySkins = skinsSection.getStringList("skin-list");
        if (legacySkins != null && !legacySkins.isEmpty()) {
            return new SkinEntry(RandomUtil.randomElement(legacySkins), null, null);
        }

        return new SkinEntry("Steve", null, null);
    }

    /**
     * Picks a random skin entry from a config pool section that may contain
     * "usernames" (list of strings) and/or "textures" (list of maps).
     */
    @Nullable
    private static SkinEntry pickFromPool(@Nullable ConfigurationSection poolSection) {
        if (poolSection == null) return null;

        List<SkinEntry> candidates = new ArrayList<>();

        // Collect username entries
        List<String> usernames = poolSection.getStringList("usernames");
        if (usernames != null) {
            for (String u : usernames) {
                candidates.add(new SkinEntry(u, null, null));
            }
        }

        // Collect texture entries
        List<?> textures = poolSection.getList("textures");
        if (textures != null) {
            for (Object obj : textures) {
                if (obj instanceof ConfigurationSection) {
                    ConfigurationSection texSec = (ConfigurationSection) obj;
                    String name = texSec.getString("name", "CustomSkin");
                    String value = texSec.getString("value");
                    String sig = texSec.getString("signature");
                    if (value != null && sig != null) {
                        candidates.add(new SkinEntry(name, value, sig));
                    }
                } else if (obj instanceof java.util.Map) {
                    @SuppressWarnings("unchecked")
                    java.util.Map<String, Object> map = (java.util.Map<String, Object>) obj;
                    String name = String.valueOf(map.getOrDefault("name", "CustomSkin"));
                    String value = (String) map.get("value");
                    String sig = (String) map.get("signature");
                    if (value != null && sig != null) {
                        candidates.add(new SkinEntry(name, value, sig));
                    }
                }
            }
        }

        if (candidates.isEmpty()) return null;
        return RandomUtil.randomElement(candidates);
    }

    // ── Name Generation v2 ──

    /**
     * Generates a random player-like name based on the config's name strategy.
     * Supports COMPOSITE, REALISTIC, FIXED_POOL, and MIXED modes.
     */
    @Nonnull
    public static String generateRandomName(@Nonnull SkyWarsTrainer plugin) {
        ConfigurationSection nameSection = plugin.getConfigManager().getMainConfig()
                .getConfigurationSection("random-names");
        if (nameSection == null) return "Bot" + RandomUtil.nextInt(1000, 9999);

        String strategy = nameSection.getString("strategy", "COMPOSITE").toUpperCase(Locale.ROOT);

        switch (strategy) {
            case "REALISTIC":
                return generateRealisticName(nameSection);
            case "FIXED_POOL":
                return generateFixedPoolName(nameSection);
            case "MIXED":
                // Randomly pick a sub-strategy
                double roll = RandomUtil.nextDouble();
                if (roll < 0.40) return generateCompositeName(nameSection);
                else if (roll < 0.75) return generateRealisticName(nameSection);
                else return generateFixedPoolName(nameSection);
            case "COMPOSITE":
            default:
                return generateCompositeName(nameSection);
        }
    }

    /** Original prefix+root+suffix strategy, now reading from composite subsection. */
    @Nonnull
    private static String generateCompositeName(@Nonnull ConfigurationSection nameSection) {
        ConfigurationSection comp = nameSection.getConfigurationSection("composite");
        List<String> prefixes, roots, suffixes;
        if (comp != null) {
            prefixes = comp.getStringList("prefixes");
            roots = comp.getStringList("roots");
            suffixes = comp.getStringList("suffixes");
        } else {
            // Legacy fallback: read directly from random-names section
            prefixes = nameSection.getStringList("prefixes");
            roots = nameSection.getStringList("roots");
            suffixes = nameSection.getStringList("suffixes");
        }

        String prefix = pickOrDefault(prefixes, "");
        String root = pickOrDefault(roots, "Player");
        String suffix = pickOrDefault(suffixes, "");

        String name = prefix + root + suffix;
        return finalizeName(name, nameSection);
    }

    /** Generates names that mimic real player username patterns. */
    @Nonnull
    private static String generateRealisticName(@Nonnull ConfigurationSection nameSection) {
        ConfigurationSection real = nameSection.getConfigurationSection("realistic");
        if (real == null) return generateCompositeName(nameSection);

        List<String> firstNames = real.getStringList("first-names");
        List<String> adjectives = real.getStringList("adjectives");
        List<String> nouns = real.getStringList("nouns");
        int yearMin = real.getInt("year-range.min", 2005);
        int yearMax = real.getInt("year-range.max", 2014);
        int numMin = real.getInt("number-range.min", 1);
        int numMax = real.getInt("number-range.max", 9999);

        // Pick a pattern at random
        double roll = RandomUtil.nextDouble();
        String name;

        if (roll < 0.25 && !firstNames.isEmpty()) {
            // firstname + numbers: "Jake2847"
            name = RandomUtil.randomElement(firstNames) + RandomUtil.nextInt(numMin, numMax);
        } else if (roll < 0.40 && !firstNames.isEmpty()) {
            // firstname + year: "Emily2011"
            name = RandomUtil.randomElement(firstNames) + RandomUtil.nextInt(yearMin, yearMax);
        } else if (roll < 0.65 && !adjectives.isEmpty() && !nouns.isEmpty()) {
            // adjective + noun: "SilentArrow"
            name = RandomUtil.randomElement(adjectives) + RandomUtil.randomElement(nouns);
        } else if (roll < 0.80 && !nouns.isEmpty()) {
            // noun + numbers: "Block987"
            name = RandomUtil.randomElement(nouns) + RandomUtil.nextInt(numMin, Math.min(numMax, 999));
        } else if (!firstNames.isEmpty() && !nouns.isEmpty()) {
            // firstname + noun: "JakeWolf"
            name = RandomUtil.randomElement(firstNames) + RandomUtil.randomElement(nouns);
        } else {
            name = "Player" + RandomUtil.nextInt(100, 9999);
        }

        return finalizeName(name, nameSection);
    }

    /** Picks from a fixed list of curated full names. */
    @Nonnull
    private static String generateFixedPoolName(@Nonnull ConfigurationSection nameSection) {
        List<String> pool = nameSection.getStringList("fixed-pool");
        if (pool == null || pool.isEmpty()) {
            return generateCompositeName(nameSection);
        }
        return finalizeName(RandomUtil.randomElement(pool), nameSection);
    }

    /**
     * Applies final formatting: length clamping, leet-speak, random caps,
     * underscore separators.
     */
    @Nonnull
    private static String finalizeName(@Nonnull String name, @Nonnull ConfigurationSection nameSection) {
        ConfigurationSection fmt = nameSection.getConfigurationSection("formatting");
        int maxLen = 16;
        double leetChance = 0.0;
        double capsChance = 0.0;
        // double underscoreChance = 0.0; // applied during generation, not post

        if (fmt != null) {
            maxLen = fmt.getInt("max-length", 16);
            leetChance = fmt.getDouble("leet-chance", 0.0);
            capsChance = fmt.getDouble("random-caps-chance", 0.0);
        }

        // Apply leet-speak
        if (RandomUtil.chance(leetChance)) {
            name = applyLeetSpeak(name);
        }

        // Apply random caps
        if (RandomUtil.chance(capsChance)) {
            name = applyRandomCaps(name);
        }

        // Clamp length
        if (name.length() > maxLen) {
            name = name.substring(0, maxLen);
        }

        // Safety
        if (name.isEmpty()) {
            name = "Bot" + RandomUtil.nextInt(1000, 9999);
        }

        return name;
    }

    private static String applyLeetSpeak(String name) {
        StringBuilder sb = new StringBuilder(name.length());
        for (char c : name.toCharArray()) {
            switch (Character.toLowerCase(c)) {
                case 'a': sb.append(RandomUtil.chance(0.5) ? '4' : c); break;
                case 'e': sb.append(RandomUtil.chance(0.5) ? '3' : c); break;
                case 'i': sb.append(RandomUtil.chance(0.5) ? '1' : c); break;
                case 'o': sb.append(RandomUtil.chance(0.5) ? '0' : c); break;
                case 's': sb.append(RandomUtil.chance(0.5) ? '5' : c); break;
                case 't': sb.append(RandomUtil.chance(0.5) ? '7' : c); break;
                default: sb.append(c);
            }
        }
        return sb.toString();
    }

    private static String applyRandomCaps(String name) {
        StringBuilder sb = new StringBuilder(name.length());
        for (int i = 0; i < name.length(); i++) {
            char c = name.charAt(i);
            if (Character.isLetter(c) && RandomUtil.chance(0.25)) {
                sb.append(Character.isUpperCase(c) ? Character.toLowerCase(c) : Character.toUpperCase(c));
            } else {
                sb.append(c);
            }
        }
        return sb.toString();
    }

    @Nonnull
    private static String pickOrDefault(@Nullable List<String> list, @Nonnull String fallback) {
        if (list == null || list.isEmpty()) return fallback;
        return RandomUtil.randomElement(list);
    }

    // ── Inner: SkinEntry ──

    private static final class SkinEntry {
        final String name;
        final String textureValue;
        final String signature;

        SkinEntry(String name, @Nullable String textureValue, @Nullable String signature) {
            this.name = name;
            this.textureValue = textureValue;
            this.signature = signature;
        }
    }

    @Override
    public String toString() {
        return "BotSkin{skin='" + skinName + "', display='" + displayName
                + "', texture=" + (isTextureBased() ? "yes" : "no") + "}";
    }
}
