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
     * <p>[FIX 2.1/2.2] Removed the redundant inline permission check. The
     * {@link org.twightlight.skywarstrainer.commands.CommandHandler} already checks
     * {@link #getPermission()} before calling execute(). The inline check used
     * "skywarstrainer.debug" while getPermission() returned "skywarstrainer.debugtoggle",
     * which was inconsistent and also missing the return statement after the
     * permission-denied message.</p>
     *
     * @param sender the command sender
     * @param args   command arguments (unused)
     */
    public void execute(@Nonnull CommandSender sender, @Nonnull String[] args) {
        // [FIX 2.1/2.2] Permission is already checked by CommandHandler via getPermission().
        // The old inline check for "skywarstrainer.debug" was inconsistent with getPermission()
        // returning "skywarstrainer.debugtoggle", and also lacked a return statement.
        // Removed entirely.

        boolean newState = !DebugLogger.isGlobalDebugEnabled();
        DebugLogger.setGlobalDebug(newState);
        sender.sendMessage("§aGlobal debug mode: " + (newState ? "§2ENABLED" : "§cDISABLED"));
    }

    @Override
    @Nonnull
    public String getPermission() {
        // [FIX 2.2] Use the existing declared permission "skywarstrainer.debug"
        // instead of the undeclared "skywarstrainer.debugtoggle"
        return "skywarstrainer.debug";
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
