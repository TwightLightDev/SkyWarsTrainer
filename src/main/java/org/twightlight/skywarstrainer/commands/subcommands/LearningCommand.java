package org.twightlight.skywarstrainer.commands.subcommands;

import org.bukkit.ChatColor;
import org.bukkit.command.CommandSender;
import org.twightlight.skywarstrainer.SkyWarsTrainer;
import org.twightlight.skywarstrainer.ai.decision.DecisionEngine.BotAction;
import org.twightlight.skywarstrainer.ai.learning.*;
import org.twightlight.skywarstrainer.bot.TrainerBot;
import org.twightlight.skywarstrainer.commands.CommandHandler;

import javax.annotation.Nonnull;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.util.*;

/**
 * /swt learning <subcommand> — Observability and control commands for the
 * reinforcement learning system.
 *
 * <p>Subcommands:</p>
 * <ul>
 *   <li>{@code status} — Show MemoryBank size, ReplayBuffer size, total games, beta, LR, top states/adjustments</li>
 *   <li>{@code reset [confirm]} — Wipe all learned data (requires confirmation)</li>
 *   <li>{@code debug <botname>} — Show live learning data for a specific bot</li>
 *   <li>{@code export} — Export Q-table as CSV for external analysis</li>
 *   <li>{@code pause} — Temporarily disable learning without clearing data</li>
 *   <li>{@code resume} — Re-enable learning</li>
 * </ul>
 */
public class LearningCommand implements SubCommand {

    private static final String PREFIX = CommandHandler.getPrefix();

    private final SkyWarsTrainer plugin;

    public LearningCommand(@Nonnull SkyWarsTrainer plugin) {
        this.plugin = plugin;
    }

    @Override
    public void execute(@Nonnull CommandSender sender, @Nonnull String[] args) {
        LearningManager lm = plugin.getLearningManager();
        if (lm == null || lm.getLearningConfig() == null) {
            sender.sendMessage(PREFIX + ChatColor.RED + "Learning system is not initialized.");
            return;
        }

        if (args.length == 0) {
            sendUsage(sender);
            return;
        }

        String sub = args[0].toLowerCase();
        switch (sub) {
            case "status":
                handleStatus(sender, lm);
                break;
            case "reset":
                handleReset(sender, lm, args);
                break;
            case "debug":
                handleDebug(sender, lm, args);
                break;
            case "export":
                handleExport(sender, lm);
                break;
            case "pause":
                handlePause(sender, lm);
                break;
            case "resume":
                handleResume(sender, lm);
                break;
            default:
                sender.sendMessage(PREFIX + ChatColor.RED + "Unknown learning subcommand: " + sub);
                sendUsage(sender);
                break;
        }
    }

    // ═════════════════════════════════════════════════════════════
    //  STATUS
    // ═════════════════════════════════════════════════════════════

    private void handleStatus(@Nonnull CommandSender sender, @Nonnull LearningManager lm) {
        MemoryBank memoryBank = lm.getSharedMemoryBank();
        ReplayBuffer replayBuffer = lm.getSharedReplayBuffer();
        LearningConfig config = lm.getLearningConfig();

        if (memoryBank == null || replayBuffer == null || config == null) {
            sender.sendMessage(PREFIX + ChatColor.RED + "Learning system components are unavailable.");
            return;
        }

        sender.sendMessage(ChatColor.GOLD + "━━━ Learning System Status ━━━");

        // Enabled / paused state
        boolean anyPaused = false;
        for (TrainerBot bot : plugin.getBotManager().getAllBots()) {
            LearningEngine module = bot.getLearningEngine();
            if (module != null && module.isLearningPaused()) {
                anyPaused = true;
                break;
            }
        }

        sender.sendMessage(ChatColor.GRAY + "Enabled: "
                + (config.isEnabled() ? ChatColor.GREEN + "YES" : ChatColor.RED + "NO")
                + (anyPaused ? ChatColor.YELLOW + " (some bots paused)" : ""));

        // Memory bank
        sender.sendMessage(ChatColor.GRAY + "Q-Table: " + ChatColor.WHITE
                + memoryBank.size() + ChatColor.GRAY + " / "
                + ChatColor.WHITE + memoryBank.getMaxCapacity()
                + ChatColor.GRAY + " entries ("
                + ChatColor.WHITE + String.format("%.1f%%", memoryBank.size() * 100.0 / memoryBank.getMaxCapacity())
                + ChatColor.GRAY + ")");

        // Replay buffer
        sender.sendMessage(ChatColor.GRAY + "Replay Buffer: " + ChatColor.WHITE
                + replayBuffer.getSize() + ChatColor.GRAY + " / "
                + ChatColor.WHITE + replayBuffer.getCapacity()
                + ChatColor.GRAY + " entries");

        // Game number and beta
        sender.sendMessage(ChatColor.GRAY + "Total Games Learned: " + ChatColor.WHITE
                + memoryBank.getCurrentGameNumber());
        sender.sendMessage(ChatColor.GRAY + "Current Beta (IS correction): " + ChatColor.WHITE
                + String.format("%.3f", replayBuffer.getBetaCurrent()));
        sender.sendMessage(ChatColor.GRAY + "Base Learning Rate: " + ChatColor.WHITE
                + String.format("%.4f", config.getLearningRate()));

        // Top 5 most-visited states
        sender.sendMessage(ChatColor.GOLD + "── Top 5 Most-Visited States ──");
        List<Map.Entry<Integer, MemoryBank.QEntry>> sortedByVisits =
                new ArrayList<Map.Entry<Integer, MemoryBank.QEntry>>(memoryBank.getAllEntries().entrySet());
        Collections.sort(sortedByVisits, new Comparator<Map.Entry<Integer, MemoryBank.QEntry>>() {
            @Override
            public int compare(Map.Entry<Integer, MemoryBank.QEntry> a,
                               Map.Entry<Integer, MemoryBank.QEntry> b) {
                return Integer.compare(b.getValue().totalVisitCount(), a.getValue().totalVisitCount());
            }
        });

        int binsPerDim = config.getBinsPerDimension();
        StateEncoder tempEncoder = new StateEncoder(binsPerDim);
        BotAction[] actions = BotAction.values();

        int shown = 0;
        for (Map.Entry<Integer, MemoryBank.QEntry> entry : sortedByVisits) {
            if (shown >= 5) break;
            int stateId = entry.getKey();
            MemoryBank.QEntry qEntry = entry.getValue();

            String stateDesc = decodeStateDescription(stateId, tempEncoder, binsPerDim);
            int bestAction = qEntry.argMaxAction();
            String bestActionName = (bestAction >= 0 && bestAction < actions.length)
                    ? actions[bestAction].name() : "?";

            sender.sendMessage(ChatColor.YELLOW + " #" + (shown + 1) + " "
                    + ChatColor.WHITE + stateDesc
                    + ChatColor.GRAY + " visits=" + qEntry.totalVisitCount()
                    + " bestQ=" + String.format("%.3f", qEntry.maxQValue())
                    + " → " + ChatColor.AQUA + bestActionName);
            shown++;
        }
        if (shown == 0) {
            sender.sendMessage(ChatColor.GRAY + "  (no entries yet)");
        }

        // Top 5 largest weight adjustments (across all bots)
        sender.sendMessage(ChatColor.GOLD + "── Top 5 Largest Active Adjustments ──");
        // We aggregate from all active bots' current adjustments
        boolean anyAdjustments = false;
        List<AdjustmentInfo> allAdj = new ArrayList<AdjustmentInfo>();
        for (TrainerBot bot : plugin.getBotManager().getAllBots()) {
            LearningEngine module = bot.getLearningEngine();
            if (module == null) continue;
            Map<BotAction, Double> adj = module.getWeightAdjustments();
            for (Map.Entry<BotAction, Double> e : adj.entrySet()) {
                double deviation = Math.abs(e.getValue() - 1.0);
                if (deviation > 0.01) {
                    allAdj.add(new AdjustmentInfo(bot.getName(), e.getKey(), e.getValue()));
                    anyAdjustments = true;
                }
            }
        }
        Collections.sort(allAdj, new Comparator<AdjustmentInfo>() {
            @Override
            public int compare(AdjustmentInfo a, AdjustmentInfo b) {
                return Double.compare(Math.abs(b.multiplier - 1.0), Math.abs(a.multiplier - 1.0));
            }
        });

        shown = 0;
        for (AdjustmentInfo info : allAdj) {
            if (shown >= 5) break;
            ChatColor color = info.multiplier > 1.0 ? ChatColor.GREEN : ChatColor.RED;
            sender.sendMessage(ChatColor.YELLOW + " #" + (shown + 1) + " "
                    + ChatColor.WHITE + info.botName
                    + ChatColor.GRAY + " → " + ChatColor.AQUA + info.action.name()
                    + ChatColor.GRAY + " = " + color + String.format("%.3f", info.multiplier)
                    + ChatColor.GRAY + "x");
            shown++;
        }
        if (!anyAdjustments) {
            sender.sendMessage(ChatColor.GRAY + "  (no significant adjustments yet)");
        }

        sender.sendMessage(ChatColor.GOLD + "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━");
    }

    // ═════════════════════════════════════════════════════════════
    //  RESET
    // ═════════════════════════════════════════════════════════════

    private void handleReset(@Nonnull CommandSender sender, @Nonnull LearningManager lm,
                             @Nonnull String[] args) {
        if (args.length < 2 || !args[1].equalsIgnoreCase("confirm")) {
            sender.sendMessage(PREFIX + ChatColor.YELLOW
                    + "This will permanently wipe ALL learned data (Q-table + replay buffer).");
            sender.sendMessage(PREFIX + ChatColor.YELLOW
                    + "Type " + ChatColor.RED + "/swt learning reset confirm"
                    + ChatColor.YELLOW + " to proceed.");
            return;
        }

        MemoryBank memoryBank = lm.getSharedMemoryBank();
        ReplayBuffer replayBuffer = lm.getSharedReplayBuffer();

        if (memoryBank == null || replayBuffer == null) {
            sender.sendMessage(PREFIX + ChatColor.RED + "Learning system components are unavailable.");
            return;
        }

        int oldQSize = memoryBank.size();
        int oldReplaySize = replayBuffer.getSize();

        // Clear the Q-table
        memoryBank.getEntriesMutable().clear();
        memoryBank.setCurrentGameNumber(0);

        // Clear the replay buffer by restoring empty data
        replayBuffer.restoreFromLoad(new org.twightlight.skywarstrainer.ai.learning.ReplayEntry[replayBuffer.getCapacity()], 0, 0);
        replayBuffer.setCurrentGameNumber(0);
        replayBuffer.setMaxPriorityEverSeen(1.0);

        // Resume all bots' learning (in case any were paused by emergency brake)
        for (TrainerBot bot : plugin.getBotManager().getAllBots()) {
            LearningEngine module = bot.getLearningEngine();
            if (module != null) {
                module.setLearningPaused(false);
            }
        }

        // Delete files on disk
        File dataFolder = plugin.getDataFolder();
        File memoryFile = new File(dataFolder, "learning_data.json");
        File memoryBackup = new File(dataFolder, "learning_data.backup.json");
        File replayFile = new File(dataFolder, "replay_buffer.json");
        File replayBackup = new File(dataFolder, "replay_buffer.backup.json");

        if (memoryFile.exists()) memoryFile.delete();
        if (memoryBackup.exists()) memoryBackup.delete();
        if (replayFile.exists()) replayFile.delete();
        if (replayBackup.exists()) replayBackup.delete();

        sender.sendMessage(PREFIX + ChatColor.GREEN + "Learning data reset successfully.");
        sender.sendMessage(PREFIX + ChatColor.GRAY + "Cleared " + oldQSize + " Q-table entries and "
                + oldReplaySize + " replay buffer entries.");
        sender.sendMessage(PREFIX + ChatColor.GRAY + "All bot learning resumed from scratch.");

        plugin.getLogger().info("[Learning] Data reset by " + sender.getName()
                + ". Cleared " + oldQSize + " Q-entries and " + oldReplaySize + " replay entries.");
    }

    // ═════════════════════════════════════════════════════════════
    //  DEBUG (per-bot)
    // ═════════════════════════════════════════════════════════════

    private void handleDebug(@Nonnull CommandSender sender, @Nonnull LearningManager lm,
                             @Nonnull String[] args) {
        if (args.length < 2) {
            sender.sendMessage(PREFIX + ChatColor.RED + "Usage: /swt learning debug <botname>");
            return;
        }

        String botName = args[1];
        TrainerBot bot = plugin.getBotManager().getBotByName(botName);
        if (bot == null) {
            sender.sendMessage(PREFIX + ChatColor.RED + "Bot not found: " + botName);
            return;
        }

        LearningEngine module = bot.getLearningEngine();
        if (module == null) {
            sender.sendMessage(PREFIX + ChatColor.RED + "Bot '" + botName + "' does not have learning enabled.");
            return;
        }

        LearningConfig config = lm.getLearningConfig();
        int binsPerDim = (config != null) ? config.getBinsPerDimension() : 3;
        StateEncoder tempEncoder = new StateEncoder(binsPerDim);

        sender.sendMessage(ChatColor.GOLD + "━━━ Learning Debug: " + bot.getName() + " ━━━");

        // Paused state
        sender.sendMessage(ChatColor.GRAY + "Learning Paused: "
                + (module.isLearningPaused() ? ChatColor.RED + "YES (emergency brake or manual)" : ChatColor.GREEN + "NO"));

        // Current state
        int stateId = module.getCurrentStateId();
        sender.sendMessage(ChatColor.GRAY + "Current State ID: " + ChatColor.WHITE
                + (stateId >= 0 ? stateId : "(not yet computed)"));

        if (stateId >= 0) {
            String stateDesc = decodeStateDescription(stateId, tempEncoder, binsPerDim);
            sender.sendMessage(ChatColor.GRAY + "State Description: " + ChatColor.WHITE + stateDesc);
        }

        // Current state vector
        double[] vec = module.getCurrentStateVector();
        if (vec != null) {
            sender.sendMessage(ChatColor.GRAY + "State Vector:");
            String[] dimNames = {
                    "HP%", "Equip", "EnemyDist", "Enemies", "Progress",
                    "Blocks", "Chests", "OnMid", "Sword", "Bow",
                    "GApple", "VoidEdge", "EnemEquip", "TimePres", "EBridge", "Alive"
            };
            StringBuilder vecStr = new StringBuilder();
            for (int i = 0; i < vec.length && i < dimNames.length; i++) {
                if (i > 0) vecStr.append(ChatColor.GRAY + ", ");
                vecStr.append(ChatColor.AQUA).append(dimNames[i]).append(ChatColor.GRAY)
                        .append("=").append(ChatColor.WHITE).append(String.format("%.2f", vec[i]));
            }
            sender.sendMessage("  " + vecStr.toString());
        }

        // Weight adjustments
        sender.sendMessage(ChatColor.GRAY + "Current Weight Adjustments:");
        Map<BotAction, Double> adjustments = module.getWeightAdjustments();
        if (adjustments.isEmpty()) {
            sender.sendMessage(ChatColor.GRAY + "  (all 1.0 — no learned adjustments)");
        } else {
            BotAction[] allActions = BotAction.values();
            for (BotAction action : allActions) {
                Double mult = adjustments.get(action);
                if (mult == null) mult = 1.0;
                ChatColor color;
                if (mult > 1.05) {
                    color = ChatColor.GREEN;
                } else if (mult < 0.95) {
                    color = ChatColor.RED;
                } else {
                    color = ChatColor.GRAY;
                }
                sender.sendMessage("  " + ChatColor.YELLOW + action.name()
                        + ChatColor.GRAY + " = " + color + String.format("%.3f", mult) + "x");
            }
        }

        // Trace and experience counts
        sender.sendMessage(ChatColor.GRAY + "Eligibility Trace Count: " + ChatColor.WHITE
                + module.getTraceCount());
        sender.sendMessage(ChatColor.GRAY + "Pending Experiences: " + ChatColor.WHITE
                + module.getPendingExperienceCount());
        sender.sendMessage(ChatColor.GRAY + "Effective Learning Rate: " + ChatColor.WHITE
                + String.format("%.5f", module.getEffectiveLearningRate()));
        sender.sendMessage(ChatColor.GRAY + "Total Games Learned: " + ChatColor.WHITE
                + module.getTotalGamesLearned());

        sender.sendMessage(ChatColor.GOLD + "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━");
    }

    // ═════════════════════════════════════════════════════════════
    //  EXPORT
    // ═════════════════════════════════════════════════════════════

    private void handleExport(@Nonnull CommandSender sender, @Nonnull LearningManager lm) {
        MemoryBank memoryBank = lm.getSharedMemoryBank();
        if (memoryBank == null) {
            sender.sendMessage(PREFIX + ChatColor.RED + "MemoryBank is unavailable.");
            return;
        }

        if (memoryBank.size() == 0) {
            sender.sendMessage(PREFIX + ChatColor.YELLOW + "Q-table is empty. Nothing to export.");
            return;
        }

        File exportFile = new File(plugin.getDataFolder(), "qtable_export.csv");
        FileWriter writer = null;
        try {
            if (!plugin.getDataFolder().exists()) {
                plugin.getDataFolder().mkdirs();
            }

            writer = new FileWriter(exportFile);
            BotAction[] allActions = BotAction.values();

            // Header
            writer.write("stateId,action,actionName,qValue,visits,lastAccessedGame,createdGame\n");

            for (Map.Entry<Integer, MemoryBank.QEntry> entry : memoryBank.getAllEntries().entrySet()) {
                int stateId = entry.getKey();
                MemoryBank.QEntry qEntry = entry.getValue();

                for (int a = 0; a < allActions.length && a < qEntry.qValues.length; a++) {
                    // Only export non-zero entries to keep file manageable
                    if (Math.abs(qEntry.qValues[a]) < 0.0001 && qEntry.visitCounts[a] == 0) {
                        continue;
                    }
                    writer.write(stateId + ","
                            + a + ","
                            + allActions[a].name() + ","
                            + String.format("%.6f", qEntry.qValues[a]) + ","
                            + qEntry.visitCounts[a] + ","
                            + qEntry.lastAccessedGame + ","
                            + qEntry.createdGame + "\n");
                }
            }

            writer.flush();
            sender.sendMessage(PREFIX + ChatColor.GREEN + "Q-table exported to: "
                    + ChatColor.WHITE + exportFile.getAbsolutePath());
            sender.sendMessage(PREFIX + ChatColor.GRAY + "Exported " + memoryBank.size() + " states.");

        } catch (IOException e) {
            sender.sendMessage(PREFIX + ChatColor.RED + "Failed to export: " + e.getMessage());
            plugin.getLogger().warning("Failed to export Q-table: " + e.getMessage());
        } finally {
            if (writer != null) {
                try {
                    writer.close();
                } catch (IOException ignored) {
                }
            }
        }
    }

    // ═════════════════════════════════════════════════════════════
    //  PAUSE / RESUME
    // ═════════════════════════════════════════════════════════════

    private void handlePause(@Nonnull CommandSender sender, @Nonnull LearningManager lm) {
        int count = 0;
        for (TrainerBot bot : plugin.getBotManager().getAllBots()) {
            LearningEngine module = bot.getLearningEngine();
            if (module != null && !module.isLearningPaused()) {
                module.setLearningPaused(true);
                count++;
            }
        }

        sender.sendMessage(PREFIX + ChatColor.YELLOW + "Learning paused for " + count
                + " bot(s). Data is preserved.");
        sender.sendMessage(PREFIX + ChatColor.GRAY + "Use " + ChatColor.YELLOW
                + "/swt learning resume" + ChatColor.GRAY + " to re-enable.");

        plugin.getLogger().info("[Learning] Paused by " + sender.getName() + " for " + count + " bots.");
    }

    private void handleResume(@Nonnull CommandSender sender, @Nonnull LearningManager lm) {
        int count = 0;
        for (TrainerBot bot : plugin.getBotManager().getAllBots()) {
            LearningEngine module = bot.getLearningEngine();
            if (module != null && module.isLearningPaused()) {
                module.setLearningPaused(false);
                count++;
            }
        }

        sender.sendMessage(PREFIX + ChatColor.GREEN + "Learning resumed for " + count + " bot(s).");
        plugin.getLogger().info("[Learning] Resumed by " + sender.getName() + " for " + count + " bots.");
    }

    // ═════════════════════════════════════════════════════════════
    //  HELPERS
    // ═════════════════════════════════════════════════════════════

    /**
     * Decodes a discretized state ID into a human-readable description showing
     * the bin level (LOW/MED/HIGH) for each dimension.
     *
     * @param stateId    the discretized state ID
     * @param encoder    a StateEncoder for bin decoding
     * @param binsPerDim bins per dimension
     * @return a compact human-readable string
     */
    private String decodeStateDescription(int stateId, @Nonnull StateEncoder encoder, int binsPerDim) {
        int[] bins = encoder.decodeToBins(stateId);
        String[] dimNames = {
                "HP", "Equip", "Dist", "Enem", "Prog",
                "Blks", "Chst", "Mid", "Swrd", "Bow",
                "Gap", "Void", "EEqp", "Time", "EBr", "Aliv"
        };
        String[] binLabels;
        if (binsPerDim == 3) {
            binLabels = new String[]{"L", "M", "H"};
        } else {
            // Generic numeric labels
            binLabels = new String[binsPerDim];
            for (int i = 0; i < binsPerDim; i++) {
                binLabels[i] = String.valueOf(i);
            }
        }

        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < bins.length && i < dimNames.length; i++) {
            if (i > 0) sb.append(" ");
            String label = (bins[i] >= 0 && bins[i] < binLabels.length)
                    ? binLabels[bins[i]] : "?";
            sb.append(dimNames[i]).append(":").append(label);
        }
        return sb.toString();
    }

    private void sendUsage(@Nonnull CommandSender sender) {
        sender.sendMessage(ChatColor.GOLD + "━━━ Learning Commands ━━━");
        sender.sendMessage(ChatColor.YELLOW + "/swt learning status"
                + ChatColor.GRAY + " — Show learning system overview");
        sender.sendMessage(ChatColor.YELLOW + "/swt learning reset confirm"
                + ChatColor.GRAY + " — Wipe all learned data");
        sender.sendMessage(ChatColor.YELLOW + "/swt learning debug <botname>"
                + ChatColor.GRAY + " — Show live data for a bot");
        sender.sendMessage(ChatColor.YELLOW + "/swt learning export"
                + ChatColor.GRAY + " — Export Q-table as CSV");
        sender.sendMessage(ChatColor.YELLOW + "/swt learning pause"
                + ChatColor.GRAY + " — Pause learning (keep data)");
        sender.sendMessage(ChatColor.YELLOW + "/swt learning resume"
                + ChatColor.GRAY + " — Resume learning");
        sender.sendMessage(ChatColor.GOLD + "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━");
    }

    @Override
    @Nonnull
    public String getPermission() {
        return "skywarstrainer.learning";
    }

    @Override
    @Nonnull
    public String getUsage() {
        return "/swt learning <status|reset|debug|export|pause|resume>";
    }

    @Override
    @Nonnull
    public String getDescription() {
        return "Manage and observe the reinforcement learning system.";
    }

    /**
     * Simple data holder for sorting weight adjustments across bots.
     */
    private static final class AdjustmentInfo {
        final String botName;
        final BotAction action;
        final double multiplier;

        AdjustmentInfo(@Nonnull String botName, @Nonnull BotAction action, double multiplier) {
            this.botName = botName;
            this.action = action;
            this.multiplier = multiplier;
        }
    }
}
