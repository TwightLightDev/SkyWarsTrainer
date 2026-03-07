package org.twightlight.skywarstrainer.commands.subcommands;

import org.bukkit.ChatColor;
import org.bukkit.command.CommandSender;
import org.twightlight.skywarstrainer.SkyWarsTrainer;
import org.twightlight.skywarstrainer.commands.CommandHandler;

import javax.annotation.Nonnull;

/**
 * /swt reload — Reloads all configuration files.
 */
public class ReloadCommand implements SubCommand {

    private final SkyWarsTrainer plugin;

    public ReloadCommand(@Nonnull SkyWarsTrainer plugin) {
        this.plugin = plugin;
    }

    @Override
    public void execute(@Nonnull CommandSender sender, @Nonnull String[] args) {
        long start = System.currentTimeMillis();

        plugin.getConfigManager().reload();
        plugin.getDifficultyConfig().load();
        if (plugin.getPersonalityConfig() != null) {
            plugin.getPersonalityConfig().load();
        }

        long elapsed = System.currentTimeMillis() - start;
        sender.sendMessage(CommandHandler.getPrefix() + ChatColor.GREEN
                + "Configuration reloaded in " + elapsed + "ms.");
    }

    @Override
    @Nonnull
    public String getPermission() {
        return "skywarstrainer.modify";
    }

    @Override
    @Nonnull
    public String getUsage() {
        return "/swt reload";
    }

    @Override
    @Nonnull
    public String getDescription() {
        return "Reload all configuration files.";
    }
}
