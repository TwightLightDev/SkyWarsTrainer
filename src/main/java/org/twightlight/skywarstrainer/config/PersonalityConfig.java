package org.twightlight.skywarstrainer.config;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.twightlight.skywarstrainer.SkyWarsTrainer;
import org.twightlight.skywarstrainer.ai.personality.Personality;
import org.twightlight.skywarstrainer.util.RandomUtil;

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
 */
public class PersonalityConfig {

    private final SkyWarsTrainer plugin;

    /** Override modifiers loaded from config. Outer key: personality name, inner: modifier map. */
    private final Map<String, Map<String, Double>> overrides;

    /** Chat messages per personality per event type. */
    private final Map<String, Map<String, List<String>>> chatMessages;

    public PersonalityConfig(@Nonnull SkyWarsTrainer plugin) {
        this.plugin = plugin;
        this.overrides = new HashMap<>();
        this.chatMessages = new HashMap<>();
    }

    public void load() {
        overrides.clear();
        chatMessages.clear();

        loadPersonalityOverrides();
        loadChatMessages();
    }

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

    public double getModifierOverride(@Nonnull String personality, @Nonnull String key,
                                      double defaultValue) {
        Map<String, Double> personalityOverrides = overrides.get(personality.toUpperCase());
        if (personalityOverrides == null) return defaultValue;
        return personalityOverrides.getOrDefault(key, defaultValue);
    }

    public boolean hasOverrides(@Nonnull String personality) {
        return overrides.containsKey(personality.toUpperCase());
    }

    /**
     * Returns a random chat message for the given personality and event type.
     *
     * <p>[FIX 2.7] Uses {@link RandomUtil#randomElement(List)} instead of
     * {@code new Random().nextInt()} to avoid creating a new Random instance every call.</p>
     *
     * @param personality the personality name
     * @param eventType   the event type (e.g., "game_start", "first_kill", "death")
     * @return a random message, or null if none configured
     */
    @javax.annotation.Nullable
    public String getRandomChatMessage(@Nonnull String personality, @Nonnull String eventType) {
        Map<String, List<String>> personalityMessages = chatMessages.get(personality.toUpperCase());
        if (personalityMessages == null) {
            personalityMessages = chatMessages.get("DEFAULT");
        }
        if (personalityMessages == null) return null;

        List<String> messages = personalityMessages.get(eventType);
        if (messages == null || messages.isEmpty()) return null;

        // [FIX 2.7] Use RandomUtil instead of new Random()
        return RandomUtil.randomElement(messages.toArray(new String[0]));
    }

    @Nonnull
    public Set<String> getChatEventTypes(@Nonnull String personality) {
        Map<String, List<String>> personalityMessages = chatMessages.get(personality.toUpperCase());
        if (personalityMessages == null) return Collections.emptySet();
        return personalityMessages.keySet();
    }
}
