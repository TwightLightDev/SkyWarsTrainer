package org.twightlight.skywarstrainer.commands.subcommands;

import net.md_5.bungee.api.chat.ClickEvent;
import net.md_5.bungee.api.chat.ComponentBuilder;
import net.md_5.bungee.api.chat.HoverEvent;
import net.md_5.bungee.api.chat.TextComponent;
import org.bukkit.ChatColor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.twightlight.skywarstrainer.SkyWarsTrainer;

import javax.annotation.Nonnull;
import java.awt.*;
import java.util.Map;

/**
 * /swt help — Shows all available commands.
 *
 * <p>For {@link Player} senders, each command line is clickable: clicking it
 * types the runnable portion of the command into the player's chat bar.
 * The runnable portion is the command stripped of its argument placeholders,
 * e.g. {@code /swt spawn [<difficulty>] [<personalities>] [<name>]} becomes
 * {@code /swt spawn} when clicked.</p>
 *
 * <p>For console senders, plain text is used (no JSON components).</p>
 */
public class HelpCommand implements SubCommand {

    private final SkyWarsTrainer plugin;
    private final Map<String, SubCommand> subCommands;

    public HelpCommand(@Nonnull SkyWarsTrainer plugin, @Nonnull Map<String, SubCommand> subCommands) {
        this.plugin = plugin;
        this.subCommands = subCommands;
    }

    @Override
    public void execute(@Nonnull CommandSender sender, @Nonnull String[] args) {
        sender.sendMessage(ChatColor.GOLD + "\u2501\u2501\u2501 SkyWarsTrainer Commands \u2501\u2501\u2501");

        boolean isPlayer = sender instanceof Player;

        for (Map.Entry<String, SubCommand> entry : subCommands.entrySet()) {
            String name = entry.getKey();
            SubCommand sub = entry.getValue();
            // Skip aliases
            if (name.equals("tp")) continue;

            String usage = sub.getUsage();
            String description = sub.getDescription();

            if (isPlayer) {
                // Build clickable component
                String clickableCommand = extractClickableCommand(usage);

                TextComponent usageComponent = new TextComponent(
                        ChatColor.YELLOW + usage);
                usageComponent.setClickEvent(new ClickEvent(
                        ClickEvent.Action.SUGGEST_COMMAND, clickableCommand));
                usageComponent.setHoverEvent(new HoverEvent(
                        HoverEvent.Action.SHOW_TEXT,
                        new ComponentBuilder(ChatColor.AQUA + "Click to type: "
                                + ChatColor.WHITE + clickableCommand).create()));

                TextComponent separator = new TextComponent(
                        ChatColor.GRAY + " \u2014 ");

                TextComponent descComponent = new TextComponent(
                        ChatColor.GRAY + description);

                TextComponent full = new TextComponent();
                full.addExtra(usageComponent);
                full.addExtra(separator);
                full.addExtra(descComponent);

                ((Player) sender).spigot().sendMessage(full);
            } else {
                // Console — plain text
                sender.sendMessage(ChatColor.YELLOW + usage
                        + ChatColor.GRAY + " \u2014 " + description);
            }
        }

        sender.sendMessage(ChatColor.GOLD + "\u2501\u2501\u2501\u2501\u2501\u2501\u2501\u2501\u2501\u2501"
                + "\u2501\u2501\u2501\u2501\u2501\u2501\u2501\u2501\u2501\u2501\u2501\u2501\u2501\u2501\u2501"
                + "\u2501\u2501\u2501\u2501\u2501\u2501\u2501\u2501\u2501\u2501");
    }

    /**
     * Extracts the "clickable" portion of a usage string by stripping
     * everything after the first argument placeholder ({@code <} or {@code [}).
     *
     * <p>Examples:
     * <ul>
     *   <li>{@code /swt spawn <difficulty> [personalities] [name]} → {@code /swt spawn}</li>
     *   <li>{@code /swt list} → {@code /swt list}</li>
     *   <li>{@code /swt validatekeys [<provider>]} → {@code /swt validatekeys}</li>
     * </ul>
     * </p>
     */
    private String extractClickableCommand(String usage) {
        // Find the first occurrence of '<' or '[' which marks a parameter
        int cutoff = usage.length();
        int angleBracket = usage.indexOf('<');
        int squareBracket = usage.indexOf('[');

        if (angleBracket >= 0 && angleBracket < cutoff) {
            cutoff = angleBracket;
        }
        if (squareBracket >= 0 && squareBracket < cutoff) {
            cutoff = squareBracket;
        }

        return usage.substring(0, cutoff).trim();
    }

    @Override
    @Nonnull
    public String getPermission() {
        return "skywarstrainer.spawn";
    }

    @Override
    @Nonnull
    public String getUsage() {
        return "/swt help";
    }

    @Override
    @Nonnull
    public String getDescription() {
        return "Show all available commands.";
    }
}
