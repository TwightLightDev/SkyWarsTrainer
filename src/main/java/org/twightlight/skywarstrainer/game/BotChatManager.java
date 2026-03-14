package org.twightlight.skywarstrainer.game;

import org.bukkit.Bukkit;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Player;
import org.twightlight.skywarstrainer.SkyWarsTrainer;
import org.twightlight.skywarstrainer.ai.personality.Personality;
import org.twightlight.skywarstrainer.ai.personality.PersonalityProfile;
import org.twightlight.skywarstrainer.bot.TrainerBot;
import org.twightlight.skywarstrainer.config.ConfigManager;
import org.twightlight.skywarstrainer.util.RandomUtil;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Manages fake chat messages sent by bots to simulate real player communication.
 *
 * [FIX] Now reads ALL chat config values from config.yml via ConfigManager:
 * - cooldown-seconds
 * - typing-speed-min-ms / typing-speed-max-ms
 * - max-delay-ticks
 * - message-chance per event type
 * - show-typing-particles
 *
 * Previously all of these were hardcoded constants.
 */
public final class BotChatManager {

    /** Cooldown tracking: botId -> last message timestamp (millis). */
    private static final Map<UUID, Long> messageCooldowns = new ConcurrentHashMap<>();

    private BotChatManager() {
        // Static utility class
    }

    /**
     * Sends a chat message from the given bot based on the event type.
     *
     * [FIX] Now uses config values for:
     * - message chance per event (chat.message-chance.*)
     * - cooldown (chat.cooldown-seconds)
     * - typing speed (chat.typing-speed-min-ms / max-ms)
     * - max delay (chat.max-delay-ticks)
     */
    public static void sendChatMessage(@Nonnull TrainerBot bot, @Nonnull String eventType) {
        SkyWarsTrainer plugin = bot.getPlugin();
        ConfigManager config = plugin.getConfigManager();

        // Check if chat is enabled
        if (!config.isChatEnabled()) return;

        // [FIX] Check message chance for this event type from config
        double chance = config.getChatChanceForEvent(eventType);
        if (!RandomUtil.chance(chance)) return;

        // [FIX] Check cooldown from config (was hardcoded 5000ms)
        long cooldownMs = config.getChatCooldownSeconds() * 1000L;
        long now = System.currentTimeMillis();
        Long lastMessage = messageCooldowns.get(bot.getBotId());
        if (lastMessage != null && (now - lastMessage) < cooldownMs) {
            return;
        }

        // Select a message based on personality and event type
        String message = selectMessage(plugin, bot, eventType);
        if (message == null || message.isEmpty()) return;

        // [FIX] Calculate typing delay from config (was hardcoded 20-80 ms/char)
        int typingDelayTicks = calculateTypingDelay(message, config);

        // Record cooldown
        messageCooldowns.put(bot.getBotId(), now);

        // Schedule the message with typing delay
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            if (bot.isAlive()) {
                broadcastBotMessage(bot, message);
            }
        }, typingDelayTicks);
    }

    @Nullable
    private static String selectMessage(@Nonnull SkyWarsTrainer plugin,
                                        @Nonnull TrainerBot bot,
                                        @Nonnull String eventType) {
        FileConfiguration messagesConfig = plugin.getConfigManager().getMessagesConfig();
        if (messagesConfig == null) return null;

        PersonalityProfile profile = bot.getProfile().getPersonalityProfile();

        // Try personality-specific messages first
        for (Personality personality : profile.getPersonalities()) {
            String path = "messages." + personality.name().toLowerCase() + "." + eventType;
            List<String> messages = messagesConfig.getStringList(path);
            if (messages != null && !messages.isEmpty()) {
                return RandomUtil.randomElement(messages.toArray(new String[0]));
            }
        }

        // Fall back to default messages
        String defaultPath = "messages.default." + eventType;
        List<String> defaultMessages = messagesConfig.getStringList(defaultPath);
        if (defaultMessages != null && !defaultMessages.isEmpty()) {
            return RandomUtil.randomElement(defaultMessages.toArray(new String[0]));
        }

        return getHardcodedFallback(eventType);
    }

    @Nullable
    private static String getHardcodedFallback(@Nonnull String eventType) {
        switch (eventType) {
            case "game_start": return "gl hf";
            case "first_kill": return "gg";
            case "death": return "gg";
            case "win": return "GG!";
            case "close_fight_won": return "that was close!";
            case "close_fight_lost": return "gg wp";
            default: return null;
        }
    }

    /**
     * Calculates the typing delay in ticks based on the message length.
     *
     * [FIX] Now reads min/max typing speed and max-delay-ticks from ConfigManager
     * instead of hardcoded values.
     */
    private static int calculateTypingDelay(@Nonnull String message, @Nonnull ConfigManager config) {
        int minMsPerChar = config.getChatTypingSpeedMinMs();
        int maxMsPerChar = config.getChatTypingSpeedMaxMs();
        int msPerChar = RandomUtil.nextInt(minMsPerChar, Math.max(minMsPerChar + 1, maxMsPerChar));
        int totalDelayMs = message.length() * msPerChar;
        // Clamp to 0.5 seconds minimum
        totalDelayMs = Math.max(500, totalDelayMs);
        // Convert to ticks (1 tick = 50ms)
        int delayTicks = totalDelayMs / 50;
        // [FIX] Cap by max-delay-ticks from config (was uncapped/hardcoded)
        int maxTicks = config.getChatMaxDelayTicks();
        return Math.min(delayTicks, maxTicks);
    }

    private static void broadcastBotMessage(@Nonnull TrainerBot bot, @Nonnull String message) {
        Player botPlayer = bot.getPlayerEntity();
        if (botPlayer == null) return;

        String formattedMessage = botPlayer.getDisplayName() + ": " + message;

        for (Player player : botPlayer.getWorld().getPlayers()) {
            if (!bot.getPlugin().getBotManager().isBot(player.getUniqueId())) {
                player.sendMessage(formattedMessage);
            }
        }

        if (bot.getProfile().isDebugMode()) {
            bot.getPlugin().getLogger().info("[CHAT] " + bot.getName() + ": " + message);
        }
    }

    public static void clearCooldown(@Nonnull UUID botId) {
        messageCooldowns.remove(botId);
    }

    public static void clearAllCooldowns() {
        messageCooldowns.clear();
    }
}
