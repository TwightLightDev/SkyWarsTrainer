package org.twightlight.skywarstrainer.game;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.configuration.file.FileConfiguration;
import org.twightlight.skywarstrainer.SkyWarsTrainerPlugin;
import org.twightlight.skywarstrainer.ai.personality.Personality;
import org.twightlight.skywarstrainer.ai.personality.PersonalityProfile;
import org.twightlight.skywarstrainer.bot.TrainerBot;
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
 * <p>Chat messages are triggered by game events (kills, deaths, game start/end)
 * and are influenced by the bot's personality. Messages are loaded from
 * messages.yml and can be customized by server admins.</p>
 *
 * <p>Features:</p>
 * <ul>
 *   <li>Personality-specific message pools (AGGRESSIVE = toxic, PASSIVE = polite, etc.)</li>
 *   <li>Simulated typing delay (20-80ms per character)</li>
 *   <li>Cooldown between messages to avoid spam</li>
 *   <li>Toggleable via config (general.enable-chat)</li>
 * </ul>
 *
 * <p>This is a static utility class. All methods are thread-safe.</p>
 */
public final class BotChatManager {

    /** Cooldown tracking: botId -> last message timestamp (millis). */
    private static final Map<UUID, Long> messageCooldowns = new ConcurrentHashMap<>();

    /** Minimum milliseconds between messages from the same bot. */
    private static final long MESSAGE_COOLDOWN_MS = 5000L;

    /** Minimum typing delay per character in milliseconds. */
    private static final int MIN_TYPING_DELAY_MS_PER_CHAR = 20;

    /** Maximum typing delay per character in milliseconds. */
    private static final int MAX_TYPING_DELAY_MS_PER_CHAR = 80;

    private BotChatManager() {
        // Static utility class
    }

    /**
     * Sends a chat message from the given bot based on the event type.
     * The message is selected from messages.yml based on the bot's personality
     * and the event type. The message is sent with a simulated typing delay.
     *
     * <p>If chat is disabled in config, this method does nothing.
     * If the bot is on cooldown, the message is skipped.</p>
     *
     * @param bot       the bot sending the message
     * @param eventType the event triggering the message (e.g., "game_start",
     *                  "first_kill", "death", "win", "close_fight_won",
     *                  "close_fight_lost")
     */
    public static void sendChatMessage(@Nonnull TrainerBot bot, @Nonnull String eventType) {
        SkyWarsTrainerPlugin plugin = bot.getPlugin();

        // Check if chat is enabled
        if (!plugin.getConfigManager().isChatEnabled()) return;

        // Check cooldown
        long now = System.currentTimeMillis();
        Long lastMessage = messageCooldowns.get(bot.getBotId());
        if (lastMessage != null && (now - lastMessage) < MESSAGE_COOLDOWN_MS) {
            return;
        }

        // Select a message based on personality and event type
        String message = selectMessage(plugin, bot, eventType);
        if (message == null || message.isEmpty()) return;

        // Calculate typing delay
        int typingDelayTicks = calculateTypingDelay(message);

        // Record cooldown
        messageCooldowns.put(bot.getBotId(), now);

        // Schedule the message with typing delay
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            if (bot.isAlive()) {
                broadcastBotMessage(bot, message);
            }
        }, typingDelayTicks);
    }

    /**
     * Selects a message from messages.yml based on the bot's personality
     * and the event type. Falls back to the "default" personality pool
     * if no personality-specific message is found.
     *
     * @param plugin    the plugin instance
     * @param bot       the bot
     * @param eventType the event type key
     * @return the selected message, or null if none available
     */
    @Nullable
    private static String selectMessage(@Nonnull SkyWarsTrainerPlugin plugin,
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

        // Hard-coded fallbacks for essential events
        return getHardcodedFallback(eventType);
    }

    /**
     * Returns a hard-coded fallback message for essential events when
     * messages.yml is missing or incomplete.
     *
     * @param eventType the event type
     * @return a fallback message, or null
     */
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
     * Simulates realistic typing speed (20-80ms per character).
     *
     * @param message the message text
     * @return delay in ticks (1 tick = 50ms)
     */
    private static int calculateTypingDelay(@Nonnull String message) {
        int msPerChar = RandomUtil.nextInt(MIN_TYPING_DELAY_MS_PER_CHAR,
                MAX_TYPING_DELAY_MS_PER_CHAR);
        int totalDelayMs = message.length() * msPerChar;
        // Clamp to 0.5-4 seconds
        totalDelayMs = Math.max(500, Math.min(4000, totalDelayMs));
        // Convert to ticks (1 tick = 50ms)
        return totalDelayMs / 50;
    }

    /**
     * Broadcasts a chat message from the bot to all players in the same world.
     * The message appears as if the bot typed it in chat.
     *
     * @param bot     the bot sending the message
     * @param message the message text
     */
    private static void broadcastBotMessage(@Nonnull TrainerBot bot, @Nonnull String message) {
        Player botPlayer = bot.getPlayerEntity();
        if (botPlayer == null) return;

        // Use Bukkit's broadcast or per-player message depending on scope
        String formattedMessage = botPlayer.getDisplayName() + ": " + message;

        // Send to all players in the same world
        for (Player player : botPlayer.getWorld().getPlayers()) {
            // Don't send to other bots
            if (!bot.getPlugin().getBotManager().isBot(player.getUniqueId())) {
                player.sendMessage(formattedMessage);
            }
        }

        if (bot.getProfile().isDebugMode()) {
            bot.getPlugin().getLogger().info("[CHAT] " + bot.getName() + ": " + message);
        }
    }

    /**
     * Clears cooldown tracking for a specific bot. Called when a bot is despawned.
     *
     * @param botId the bot's unique ID
     */
    public static void clearCooldown(@Nonnull UUID botId) {
        messageCooldowns.remove(botId);
    }

    /**
     * Clears all cooldown tracking. Called on plugin disable.
     */
    public static void clearAllCooldowns() {
        messageCooldowns.clear();
    }
}
