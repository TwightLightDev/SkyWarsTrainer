package org.twightlight.skywarstrainer.commands.subcommands;

import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.twightlight.skywarstrainer.SkyWarsTrainer;
import org.twightlight.skywarstrainer.ai.personality.Personality;

import javax.annotation.Nonnull;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Tab completion handler for the /swt command.
 * Provides context-aware suggestions for all subcommands and their arguments.
 */
public class TabCompleter implements org.bukkit.command.TabCompleter {

    private final SkyWarsTrainer plugin;

    private static final List<String> SUBCOMMANDS = Arrays.asList(
            "spawn", "remove", "list", "difficulty", "personality",
            "stats", "debug", "fill", "pause", "teleport", "test",
            "reload", "help", "learning"
    );

    private static final List<String> DIFFICULTIES = Arrays.asList(
            "BEGINNER", "EASY", "MEDIUM", "HARD", "EXPERT"
    );

    private static final List<String> PERSONALITY_NAMES;
    static {
        PERSONALITY_NAMES = new ArrayList<>();
        for (Personality p : Personality.values()) {
            PERSONALITY_NAMES.add(p.name());
        }
    }

    private static final List<String> PERSONALITY_ACTIONS = Arrays.asList("add", "remove", "set");
    private static final List<String> TEST_TYPES = Arrays.asList("combat", "bridge", "loot", "flee");

    // ── NEW: Learning subcommand completions ──
    private static final List<String> LEARNING_SUBCOMMANDS = Arrays.asList(
            "status", "reset", "debug", "export", "pause", "resume"
    );

    public TabCompleter(@Nonnull SkyWarsTrainer plugin) {
        this.plugin = plugin;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        List<String> completions = new ArrayList<>();

        if (args.length == 1) {
            // First arg: subcommand name
            String partial = args[0].toLowerCase();
            completions.addAll(SUBCOMMANDS.stream()
                    .filter(s -> s.startsWith(partial))
                    .collect(Collectors.toList()));
        } else if (args.length >= 2) {
            String sub = args[0].toLowerCase();
            String partial = args[args.length - 1].toLowerCase();

            switch (sub) {
                case "spawn":
                    if (args.length == 2) {
                        completions.addAll(filterStartsWith(DIFFICULTIES, partial));
                    } else if (args.length == 3) {
                        completions.addAll(filterStartsWith(PERSONALITY_NAMES, partial));
                    }
                    break;

                case "remove":
                    if (args.length == 2) {
                        completions.add("all");
                        completions.addAll(filterStartsWith(plugin.getBotManager().getBotNames(), partial));
                    }
                    break;

                case "difficulty":
                    if (args.length == 2) {
                        completions.addAll(filterStartsWith(plugin.getBotManager().getBotNames(), partial));
                    } else if (args.length == 3) {
                        completions.addAll(filterStartsWith(DIFFICULTIES, partial));
                    }
                    break;

                case "personality":
                    if (args.length == 2) {
                        completions.addAll(filterStartsWith(plugin.getBotManager().getBotNames(), partial));
                    } else if (args.length == 3) {
                        completions.addAll(filterStartsWith(PERSONALITY_ACTIONS, partial));
                    } else if (args.length == 4) {
                        completions.addAll(filterStartsWith(PERSONALITY_NAMES, partial));
                    }
                    break;

                case "stats":
                case "debug":
                case "pause":
                case "teleport":
                case "tp":
                    if (args.length == 2) {
                        if (sub.equals("pause")) completions.add("all");
                        completions.addAll(filterStartsWith(plugin.getBotManager().getBotNames(), partial));
                    }
                    break;

                case "fill":
                    if (args.length == 3) {
                        completions.addAll(filterStartsWith(DIFFICULTIES, partial));
                    } else if (args.length == 4) {
                        completions.addAll(filterStartsWith(PERSONALITY_NAMES, partial));
                    }
                    break;

                case "test":
                    if (args.length == 2) {
                        completions.addAll(filterStartsWith(TEST_TYPES, partial));
                    } else if (args.length == 3) {
                        completions.addAll(filterStartsWith(DIFFICULTIES, partial));
                    }
                    break;

                // ── NEW: learning subcommand tab completion ──
                case "learning":
                    if (args.length == 2) {
                        // /swt learning <subcommand>
                        completions.addAll(filterStartsWith(LEARNING_SUBCOMMANDS, partial));
                    } else if (args.length == 3) {
                        String learningSub = args[1].toLowerCase();
                        if ("debug".equals(learningSub)) {
                            // /swt learning debug <botname>
                            completions.addAll(filterStartsWith(plugin.getBotManager().getBotNames(), partial));
                        } else if ("reset".equals(learningSub)) {
                            // /swt learning reset confirm
                            completions.addAll(filterStartsWith(
                                    Arrays.asList("confirm"), partial));
                        }
                    }
                    break;
            }
        }

        return completions;
    }

    private List<String> filterStartsWith(List<String> options, String partial) {
        return options.stream()
                .filter(s -> s.toLowerCase().startsWith(partial))
                .collect(Collectors.toList());
    }
}
