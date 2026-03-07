package org.twightlight.skywarstrainer.commands.subcommands;

import org.bukkit.ChatColor;
import org.bukkit.command.CommandSender;
import org.twightlight.skywarstrainer.SkyWarsTrainer;
import org.twightlight.skywarstrainer.ai.personality.Personality;
import org.twightlight.skywarstrainer.ai.personality.PersonalityConflictTable;
import org.twightlight.skywarstrainer.bot.TrainerBot;
import org.twightlight.skywarstrainer.commands.CommandHandler;

import javax.annotation.Nonnull;
import java.util.Arrays;
import java.util.List;

/**
 * /swt personality <name> <add|remove|set> <personality> — Modifies bot personalities.
 */
public class PersonalityCommand implements SubCommand {

    private final SkyWarsTrainer plugin;

    public PersonalityCommand(@Nonnull SkyWarsTrainer plugin) {
        this.plugin = plugin;
    }

    @Override
    public void execute(@Nonnull CommandSender sender, @Nonnull String[] args) {
        if (args.length < 3) {
            sender.sendMessage(CommandHandler.getPrefix() + ChatColor.RED + "Usage: " + getUsage());
            return;
        }

        TrainerBot bot = plugin.getBotManager().getBotByName(args[0]);
        if (bot == null) {
            sender.sendMessage(CommandHandler.getPrefix() + ChatColor.RED + "Bot not found: " + args[0]);
            return;
        }

        String action = args[1].toLowerCase();
        String personalityName = args[2].toUpperCase();

        Personality personality = Personality.fromString(personalityName);
        if (personality == null && !action.equals("set")) {
            sender.sendMessage(CommandHandler.getPrefix() + ChatColor.RED + "Invalid personality: " + personalityName);
            sender.sendMessage(CommandHandler.getPrefix() + "Valid: " + Arrays.toString(Personality.values()));
            return;
        }

        switch (action) {
            case "add":
                // Check conflicts with existing personalities
                List<String> existing = bot.getProfile().getPersonalityNames();
                for (String existingName : existing) {
                    Personality existingP = Personality.fromString(existingName);
                    if (existingP != null && PersonalityConflictTable.conflicts(existingP, personality)) {
                        sender.sendMessage(CommandHandler.getPrefix() + ChatColor.RED
                                + personalityName + " conflicts with " + existingName);
                        return;
                    }
                }
                if (existing.size() >= 3) {
                    sender.sendMessage(CommandHandler.getPrefix() + ChatColor.RED + "Bot already has 3 personalities (max).");
                    return;
                }
                bot.getProfile().addPersonality(personalityName);
                sender.sendMessage(CommandHandler.getPrefix() + ChatColor.GREEN + "Added "
                        + ChatColor.YELLOW + personalityName
                        + ChatColor.GREEN + " to " + bot.getName());
                break;

            case "remove":
                if (bot.getProfile().removePersonality(personalityName)) {
                    sender.sendMessage(CommandHandler.getPrefix() + ChatColor.GREEN + "Removed "
                            + ChatColor.YELLOW + personalityName
                            + ChatColor.GREEN + " from " + bot.getName());
                } else {
                    sender.sendMessage(CommandHandler.getPrefix() + ChatColor.RED + bot.getName()
                            + " doesn't have personality: " + personalityName);
                }
                break;

            case "set":
                // Set all personalities from comma-separated list
                String[] personalities = args[2].split(",");
                bot.getProfile().setPersonalities(Arrays.asList(personalities));
                sender.sendMessage(CommandHandler.getPrefix() + ChatColor.GREEN + "Set "
                        + bot.getName() + "'s personalities to: "
                        + ChatColor.YELLOW + Arrays.toString(personalities));
                break;

            default:
                sender.sendMessage(CommandHandler.getPrefix() + ChatColor.RED + "Invalid action: " + action);
                sender.sendMessage(CommandHandler.getPrefix() + "Valid actions: add, remove, set");
        }
    }

    @Override
    @Nonnull
    public String getPermission() {
        return "skywarstrainer.modify";
    }

    @Override
    @Nonnull
    public String getUsage() {
        return "/swt personality <botName> <add|remove|set> <personality>";
    }

    @Override
    @Nonnull
    public String getDescription() {
        return "Modify a bot's personality set.";
    }
}
