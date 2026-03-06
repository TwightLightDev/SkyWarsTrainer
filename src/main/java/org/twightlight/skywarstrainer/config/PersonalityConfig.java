package org.twightlight.skywarstrainer.config;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.twightlight.skywarstrainer.SkyWarsTrainerPlugin;
import org.twightlight.skywarstrainer.ai.personality.Personality;

import javax.annotation.Nonnull;
import java.io.File;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.*;

/**
 * Loads personality configuration overrides from {@code personalities.yml}.
 *
 * <p>While the {@link Personality} enum defines default modifiers, server admins
 * can override specific modifier values via the config file. This allows
 * fine-tuning personality behavior without code changes.</p>
 *
 * <p>Config format example:
 * <pre>
 * personalities:
 *   AGGRESSIVE:
 *     modifiers:
 *       FIGHT: 1.8
 *       HUNT: 2.0
 *       LOOT: 0.5
 *       FLEE: 0.4
 *   PASSIVE:
 *     modifiers:
 *       FIGHT: 0.4
 *       FLEE: 2.0
 * </pre></p>
 */
public class PersonalityConfig {

    private final SkyWarsTrainerPlugin plugin;

    /** Override modifiers loaded from config. Outer key: personality name, inner: modifier map. */
    private final Map<String, Map<String, Double>> overrides;

    /** Chat messages per personality per event type. */
    private final Map<String, Map<String, List<String>>> chatMessages;

    /**
     * Creates a new PersonalityConfig.
     *
     * @param plugin the owning plugin
     */
    public PersonalityConfig(@Nonnull SkyWarsTrainerPlugin plugin) {
        this.plugin = plugin;
        this.overrides = new HashMap<>();
        this.chatMessages = new HashMap<>();
    }

    /**
     * Loads personality configuration from personalities.yml and messages.yml.
     */
    public void load() {
        overrides.clear();
        chatMessages.clear();

        loadPersonalityOverrides();
        loadChatMessages();
    }

    /**
     * Loads modifier overrides from personalities.yml.
     */
    private void loadPersonalityOverrides() {
        File file = new File(plugin.getDataFolder(), "personalities.yml");
        if (!file.exists()) {
            plugin.saveResource("personalities.yml", false);
        }

        FileConfiguration config = YamlConfiguration.loadConfiguration(file);
        InputStream defaultStream = plugin.getResource("personalities.yml");
        if (defaultStream != null) {
            config.setDefaults(YamlConfiguration.loadConfiguration(
                    new InputStreamReader(defaultStream, StandardCharsets.UTF_8)));
        }

        ConfigurationSection personalitiesSection = config.getConfigurationSection("personalities");
        if (personalitiesSection == null) return;

        for (String personalityName : personalitiesSection.getKeys(false)) {
            ConfigurationSection modSection = personalitiesSection.getConfigurationSection(
                    personalityName + ".modifiers");
            if (modSection == null) continue;

            Map<String, Double> modifiers = new HashMap<>();
            for (String key : modSection.getKeys(false)) {
                modifiers.put(key, modSection.getDouble(key, 1.0));
            }
            overrides.put(personalityName.toUpperCase(), modifiers);
        }

        plugin.getLogger().info("Loaded personality overrides for " + overrides.size() + " personalities.");
    }

    /**
     * Loads chat messages from messages.yml organized by personality and event type.
     */
    private void loadChatMessages() {
        FileConfiguration messagesConfig = plugin.getConfigManager().getMessagesConfig();
        if (messagesConfig == null) return;

        ConfigurationSection chatSection = messagesConfig.getConfigurationSection("chat");
        if (chatSection == null) return;

        for (String personalityName : chatSection.getKeys(false)) {
            ConfigurationSection personalityChat = chatSection.getConfigurationSection(personalityName);
            if (personalityChat == null) continue;

            Map<String, List<String>> eventMessages = new HashMap<>();
            for (String eventType : personalityChat.getKeys(false)) {
                List<String> messages = personalityChat.getStringList(eventType);
                if (!messages.isEmpty()) {
                    eventMessages.put(eventType, messages);
                }
            }
            chatMessages.put(personalityName.toUpperCase(), eventMessages);
        }

        plugin.getLogger().info("Loaded chat messages for " + chatMessages.size() + " personality profiles.");
    }

    /**
     * Returns the override modifier for a personality and key, or the default value.
     *
     * @param personality the personality name
     * @param key         the modifier key
     * @param defaultValue the default if no override exists
     * @return the modifier value
     */
    public double getModifierOverride(@Nonnull String personality, @Nonnull String key,
                                      double defaultValue) {
        Map<String, Double> personalityOverrides = overrides.get(personality.toUpperCase());
        if (personalityOverrides == null) return defaultValue;
        return personalityOverrides.getOrDefault(key, defaultValue);
    }

    /**
     * Returns whether there are overrides for a given personality.
     *
     * @param personality the personality name
     * @return true if overrides exist
     */
    public boolean hasOverrides(@Nonnull String personality) {
        return overrides.containsKey(personality.toUpperCase());
    }

    /**
     * Returns a random chat message for the given personality and event type.
     *
     * @param personality the personality name
     * @param eventType   the event type (e.g., "game_start", "first_kill", "death")
     * @return a random message, or null if none configured
     */
    @javax.annotation.Nullable
    public String getRandomChatMessage(@Nonnull String personality, @Nonnull String eventType) {
        Map<String, List<String>> personalityMessages = chatMessages.get(personality.toUpperCase());
        if (personalityMessages == null) {
            // Fall back to "DEFAULT" personality messages
            personalityMessages = chatMessages.get("DEFAULT");
        }
        if (personalityMessages == null) return null;

        List<String> messages = personalityMessages.get(eventType);
        if (messages == null || messages.isEmpty()) return null;

        return messages.get(new Random().nextInt(messages.size()));
    }

    /**
     * Returns all configured chat event types for a personality.
     *
     * @param personality the personality name
     * @return set of event type keys
     */
    @Nonnull
    public Set<String> getChatEventTypes(@Nonnull String personality) {
        Map<String, List<String>> personalityMessages = chatMessages.get(personality.toUpperCase());
        if (personalityMessages == null) return Collections.emptySet();
        return personalityMessages.keySet();
    }
}
