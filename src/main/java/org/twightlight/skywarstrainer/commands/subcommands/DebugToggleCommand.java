package org.twightlight.skywarstrainer.commands.subcommands;

import org.bukkit.command.CommandSender;
import org.twightlight.skywarstrainer.util.DebugLogger;

import javax.annotation.Nonnull;

/**
 * Subcommand: /swt debugtoggle
 * Toggles global debug mode on/off without editing config.yml.
 */
public class DebugToggleCommand implements SubCommand {
    /**
     * Executes the debug toggle command.
     *
     * @param sender the command sender
     * @param args   command arguments (unused)
     * @return true if executed successfully
     */
    public void execute(@Nonnull CommandSender sender, @Nonnull String[] args) {
        if (!sender.hasPermission("skywarstrainer.debug")) {
            sender.sendMessage("§cYou don't have permission to toggle debug mode.");
        }

        boolean newState = !DebugLogger.isGlobalDebugEnabled();
        DebugLogger.setGlobalDebug(newState);
        sender.sendMessage("§aGlobal debug mode: " + (newState ? "§2ENABLED" : "§cDISABLED"));
    }

    @Override
    @Nonnull
    public String getPermission() {
        return "skywarstrainer.debugtoggle";
    }

    @Override
    @Nonnull
    public String getUsage() {
        return "/swt debugtoggle";
    }

    @Override
    @Nonnull
    public String getDescription() {
        return "Set debug state.";
    }
}
