package org.twightlight.skywarstrainer.commands;

import org.bukkit.ChatColor;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.twightlight.skywarstrainer.SkyWarsTrainer;
import org.twightlight.skywarstrainer.commands.subcommands.*;

import javax.annotation.Nonnull;
import java.util.HashMap;
import java.util.Map;

/**
 * Main command handler for the /swt command. Delegates to subcommand handlers
 * based on the first argument.
 *
 * <p>Subcommands:
 * spawn, remove, list, difficulty, personality, stats, debug,
 * preset, fill, pause, teleport, test, reload</p>
 */
public class CommandHandler implements CommandExecutor {

    private static final String PREFIX = ChatColor.GOLD + "[SkyWarsTrainer] " + ChatColor.RESET;

    private final SkyWarsTrainer plugin;
    private final Map<String, SubCommand> subCommands;

    /**
     * Creates a new CommandHandler and registers all subcommands.
     *
     * @param plugin the owning plugin
     */
    public CommandHandler(@Nonnull SkyWarsTrainer plugin) {
        this.plugin = plugin;
        this.subCommands = new HashMap<>();
        registerSubCommands();
    }

    /**
     * Registers all subcommand implementations.
     */
    private void registerSubCommands() {
        subCommands.put("spawn", new SpawnCommand(plugin));
        subCommands.put("remove", new RemoveCommand(plugin));
        subCommands.put("list", new ListCommand(plugin));
        subCommands.put("difficulty", new DifficultyCommand(plugin));
        subCommands.put("personality", new PersonalityCommand(plugin));
        subCommands.put("stats", new StatsCommand(plugin));
        subCommands.put("debug", new DebugCommand(plugin));
        subCommands.put("fill", new FillCommand(plugin));
        subCommands.put("pause", new PauseCommand(plugin));
        subCommands.put("teleport", new TeleportCommand(plugin));
        subCommands.put("tp", new TeleportCommand(plugin));
        subCommands.put("test", new TestCommand(plugin));
        subCommands.put("reload", new ReloadCommand(plugin));
        subCommands.put("help", new HelpCommand(plugin, subCommands));
        subCommands.put("debugtoggle", new DebugToggleCommand());
        subCommands.put("learning", new LearningCommand(plugin));
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (args.length == 0) {
            sendHelp(sender);
            return true;
        }

        String subName = args[0].toLowerCase();
        SubCommand sub = subCommands.get(subName);

        if (sub == null) {
            sender.sendMessage(PREFIX + ChatColor.RED + "Unknown subcommand: " + subName);
            sender.sendMessage(PREFIX + "Use " + ChatColor.YELLOW + "/swt help" + ChatColor.RESET + " for available commands.");
            return true;
        }

        // Check permission
        if (!sender.hasPermission(sub.getPermission())) {
            sender.sendMessage(PREFIX + ChatColor.RED + "You don't have permission to use this command.");
            return true;
        }

        // Strip the subcommand name from args
        String[] subArgs = new String[args.length - 1];
        System.arraycopy(args, 1, subArgs, 0, subArgs.length);

        try {
            sub.execute(sender, subArgs);
        } catch (Exception e) {
            sender.sendMessage(PREFIX + ChatColor.RED + "An error occurred: " + e.getMessage());
            plugin.getLogger().warning("Error executing /swt " + subName + ": " + e.getMessage());
        }

        return true;
    }

    /**
     * Sends the help overview to the sender.
     */
    private void sendHelp(CommandSender sender) {
        sender.sendMessage(ChatColor.GOLD + "━━━ SkyWarsTrainer v" + plugin.getDescription().getVersion() + " ━━━");
        sender.sendMessage(ChatColor.YELLOW + "/swt help" + ChatColor.GRAY + " — Show all commands");
        sender.sendMessage(ChatColor.YELLOW + "/swt spawn <difficulty> [personalities] [name]" + ChatColor.GRAY + " — Spawn a bot");
        sender.sendMessage(ChatColor.YELLOW + "/swt remove <name|all>" + ChatColor.GRAY + " — Remove bots");
        sender.sendMessage(ChatColor.YELLOW + "/swt list" + ChatColor.GRAY + " — List active bots");
        sender.sendMessage(ChatColor.GOLD + "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━");
    }

    /**
     * Returns the map of registered subcommands.
     */
    @Nonnull
    public Map<String, SubCommand> getSubCommands() {
        return subCommands;
    }

    /**
     * Returns the prefix used for chat messages.
     */
    @Nonnull
    public static String getPrefix() {
        return PREFIX;
    }
}
