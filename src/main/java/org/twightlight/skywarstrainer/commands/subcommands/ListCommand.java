package org.twightlight.skywarstrainer.commands.subcommands;

import org.bukkit.ChatColor;
import org.bukkit.command.CommandSender;
import org.twightlight.skywarstrainer.SkyWarsTrainer;
import org.twightlight.skywarstrainer.bot.TrainerBot;
import org.twightlight.skywarstrainer.commands.CommandHandler;

import javax.annotation.Nonnull;
import java.util.List;

/**
 * /swt list — Lists all active bots with their details.
 */
public class ListCommand implements SubCommand {

    private final SkyWarsTrainer plugin;

    public ListCommand(@Nonnull SkyWarsTrainer plugin) {
        this.plugin = plugin;
    }

    @Override
    public void execute(@Nonnull CommandSender sender, @Nonnull String[] args) {
        List<TrainerBot> bots = plugin.getBotManager().getAllBots();

        if (bots.isEmpty()) {
            sender.sendMessage(CommandHandler.getPrefix() + ChatColor.GRAY + "No active bots.");
            return;
        }

        sender.sendMessage(ChatColor.GOLD + "━━━ Active Bots (" + bots.size() + ") ━━━");
        for (TrainerBot bot : bots) {
            String status = bot.isAlive() ? ChatColor.GREEN + "ALIVE" : ChatColor.RED + "DEAD";
            String paused = bot.getProfile().isPaused() ? ChatColor.YELLOW + " [PAUSED]" : "";
            sender.sendMessage(ChatColor.YELLOW + " • " + ChatColor.WHITE + bot.getName()
                    + ChatColor.GRAY + " [" + bot.getProfile().getDifficulty().name() + "] "
                    + ChatColor.GRAY + bot.getProfile().getPersonalityNames()
                    + " " + status + paused
                    + ChatColor.GRAY + " K:" + bot.getProfile().getKills()
                    + " D:" + bot.getProfile().getDeaths());
        }
        sender.sendMessage(ChatColor.GOLD + "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━");
    }

    @Override
    @Nonnull
    public String getPermission() {
        return "skywarstrainer.list";
    }

    @Override
    @Nonnull
    public String getUsage() {
        return "/swt list";
    }

    @Override
    @Nonnull
    public String getDescription() {
        return "List all active trainer bots.";
    }
}
