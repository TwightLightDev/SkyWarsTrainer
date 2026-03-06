package org.twightlight.skywarstrainer.game;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.twightlight.skywarstrainer.SkyWarsTrainerPlugin;
import org.twightlight.skywarstrainer.bot.TrainerBot;
import org.twightlight.skywarstrainer.config.PersonalityConfig;
import org.twightlight.skywarstrainer.util.RandomUtil;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.List;

/**
 * Manages fake chat messages sent by bots to simulate real player communication.
 * Messages are pulled from the PersonalityConfig's messages.yml data.
 *
 * <p>Chat messages are delayed to simulate typing time and have cooldowns
 * to prevent spam.</p>
 */
public class BotChatManager {

    private static final long MIN_CHAT_COOLDOWN_MS = 5000; // 5 seconds between messages

    /**
     * Sends a chat message from the bot for the given event type.
     * The message is selected based on the bot's personality.
     *
     * @param bot       the bot sending the message
     * @param eventType the event type (e.g., "game_start", "first_kill", "death", "win")
     */
    public static void sendChatMessage(@Nonnull TrainerBot bot, @Nonnull String eventType) {
        SkyWarsTrainerPlugin plugin = bot.getPlugin();
        if (!plugin.getConfigManager().isChatEnabled()) return;

        PersonalityConfig personalityConfig = plugin.getPersonalityConfig();
        if (personalityConfig == null) return;

        // Try to find a message for the bot's personality
        String message = null;
        List<String> personalities = bot.getProfile().getPersonalityNames();
        for (String personality : personalities) {
            message = personalityConfig.getRandomChatMessage(personality, eventType);
            if (message != null) break;
        }

        // Fallback to DEFAULT
        if (message == null) {
            message = personalityConfig.getRandomChatMessage("DEFAULT", eventType);
        }

        if (message == null) return;

        // Simulate typing delay (20-80ms per character)
        int charCount = message.length();
        long typingDelayMs = (long) (charCount * RandomUtil.nextDouble(20.0, 80.0));
        long typingDelayTicks = Math.max(1, typingDelayMs / 50);

        String finalMessage = message;
        String botName = bot.getName();

        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            // Broadcast as if the bot said it in chat
            Player botPlayer = bot.getPlayerEntity();
            if (botPlayer != null && bot.isAlive()) {
                // Use AsyncPlayerChatEvent format or just broadcast
                String chatFormat = "<" + botName + "> " + finalMessage;
                for (Player online : Bukkit.getOnlinePlayers()) {
                    online.sendMessage(chatFormat);
                }
            }
        }, typingDelayTicks);
    }
}
