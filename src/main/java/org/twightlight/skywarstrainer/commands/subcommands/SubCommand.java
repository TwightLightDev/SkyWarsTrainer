package org.twightlight.skywarstrainer.commands.subcommands;

import org.bukkit.command.CommandSender;

import javax.annotation.Nonnull;

/**
 * Interface for all /swt subcommands.
 */
public interface SubCommand {

    /**
     * Executes the subcommand.
     *
     * @param sender the command sender
     * @param args   the arguments (excluding the subcommand name itself)
     */
    void execute(@Nonnull CommandSender sender, @Nonnull String[] args);

    /**
     * Returns the permission node required to use this subcommand.
     *
     * @return the permission string
     */
    @Nonnull
    String getPermission();

    /**
     * Returns a brief usage string for this subcommand.
     *
     * @return usage string
     */
    @Nonnull
    String getUsage();

    /**
     * Returns a brief description of what this subcommand does.
     *
     * @return description
     */
    @Nonnull
    String getDescription();
}
