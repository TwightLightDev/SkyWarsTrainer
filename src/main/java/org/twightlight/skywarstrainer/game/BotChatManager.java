package org.twightlight.skywarstrainer.game;

import org.bukkit.Bukkit;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Player;
import org.twightlight.skywarstrainer.SkyWarsTrainer;
import org.twightlight.skywarstrainer.ai.personality.Personality;
import org.twightlight.skywarstrainer.ai.personality.PersonalityProfile;
import org.twightlight.skywarstrainer.bot.TrainerBot;
import org.twightlight.skywarstrainer.config.ConfigManager;
import org.twightlight.skywarstrainer.config.PersonalityConfig;
import org.twightlight.skywarstrainer.util.RandomUtil;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Manages fake chat messages sent by bots to simulate real player communication.
 */
public final class BotChatManager {

    private static final Map<UUID, Long> messageCooldowns = new ConcurrentHashMap<>();

    private BotChatManager() {
    }

    public static void sendChatMessage(@Nonnull TrainerBot bot, @Nonnull String eventType) {
        SkyWarsTrainer plugin = bot.getPlugin();
        ConfigManager config = plugin.getConfigManager();

        if (!config.isChatEnabled()) return;

        double chance = config.getChatChanceForEvent(eventType);
        if (!RandomUtil.chance(chance)) return;

        long cooldownMs = config.getChatCooldownSeconds() * 1000L;
        long now = System.currentTimeMillis();
        Long lastMessage = messageCooldowns.get(bot.getBotId());
        if (lastMessage != null && (now - lastMessage) < cooldownMs) {
            return;
        }

        String message = selectMessage(plugin, bot, eventType);
        if (message == null || message.isEmpty()) return;

        int typingDelayTicks = calculateTypingDelay(message, config);

        messageCooldowns.put(bot.getBotId(), now);

        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            if (bot.isAlive()) {
                broadcastBotMessage(bot, message);
            }
        }, typingDelayTicks);
    }

    /**
     * Selects a message for the given bot and event type.
     *
     * <p>[FIX 6.2] First tries personality-specific messages via PersonalityConfig,
     * then falls back to the current messages.yml path resolution, then to hardcoded
     * fallbacks.</p>
     */
    @Nullable
    private static String selectMessage(@Nonnull SkyWarsTrainer plugin,
                                        @Nonnull TrainerBot bot,
                                        @Nonnull String eventType) {
        PersonalityProfile profile = bot.getProfile().getPersonalityProfile();

        // [FIX 6.2] Try PersonalityConfig personality-specific chat messages first
        PersonalityConfig personalityConfig = plugin.getPersonalityConfig();
        if (personalityConfig != null) {
            for (Personality personality : profile.getPersonalities()) {
                String msg = personalityConfig.getRandomChatMessage(personality.name(), eventType);
                if (msg != null && !msg.isEmpty()) {
                    return msg;
                }
            }
        }

        // Fall back to messages.yml direct path resolution
        FileConfiguration messagesConfig = plugin.getConfigManager().getMessagesConfig();
        if (messagesConfig != null) {
            for (Personality personality : profile.getPersonalities()) {
                String path = "messages." + personality.name().toLowerCase() + "." + eventType;
                List<String> messages = messagesConfig.getStringList(path);
                if (messages != null && !messages.isEmpty()) {
                    return RandomUtil.randomElement(messages.toArray(new String[0]));
                }
            }

            String defaultPath = "messages.default." + eventType;
            List<String> defaultMessages = messagesConfig.getStringList(defaultPath);
            if (defaultMessages != null && !defaultMessages.isEmpty()) {
                return RandomUtil.randomElement(defaultMessages.toArray(new String[0]));
            }
        }

        return getHardcodedFallback(eventType);
    }

    /**
     * Returns a hardcoded fallback message for the given event type.
     *
     * <p>[FIX 3.1] Added case for "kill" (non-first kills).</p>
     */
    @Nullable
    private static String getHardcodedFallback(@Nonnull String eventType) {
        switch (eventType) {
            case "game_start": return "gl hf";
            case "first_kill": return "gg";
            case "kill": return "ez"; // [FIX 3.1] Fallback for non-first kills
            case "death": return "gg";
            case "win": return "GG!";
            case "close_fight_won": return "that was close!";
            case "close_fight_lost": return "gg wp";
            default: return null;
        }
    }

    private static int calculateTypingDelay(@Nonnull String message, @Nonnull ConfigManager config) {
        int minMsPerChar = config.getChatTypingSpeedMinMs();
        int maxMsPerChar = config.getChatTypingSpeedMaxMs();
        int msPerChar = RandomUtil.nextInt(minMsPerChar, Math.max(minMsPerChar + 1, maxMsPerChar));
        int totalDelayMs = message.length() * msPerChar;
        totalDelayMs = Math.max(500, totalDelayMs);
        int delayTicks = totalDelayMs / 50;
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
