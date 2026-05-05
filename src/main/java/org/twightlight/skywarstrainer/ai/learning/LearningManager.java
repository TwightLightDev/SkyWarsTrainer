package org.twightlight.skywarstrainer.ai.learning;

import org.bukkit.Bukkit;
import org.twightlight.skywarstrainer.SkyWarsTrainer;

import javax.annotation.Nullable;
import java.util.logging.Level;

public class LearningManager {
    private final SkyWarsTrainer plugin;

    private LearningConfig learningConfig;
    private MemoryBank sharedMemoryBank;
    private ReplayBuffer sharedReplayBuffer;

    public LearningManager(SkyWarsTrainer plugin) {
        this.plugin = plugin;
        try {
            this.learningConfig = new LearningConfig(plugin);
            this.sharedMemoryBank = new MemoryBank(learningConfig);
            this.sharedReplayBuffer = new ReplayBuffer(learningConfig);

            // [FIX 3.5] Only load learning data if learning is enabled.
            // Previously LearningSerializer.load() was called unconditionally,
            // but the auto-save timer was properly gated. Now both are consistent.
            if (learningConfig.isEnabled()) {
                LearningSerializer.load(plugin, sharedMemoryBank, sharedReplayBuffer);
                plugin.getLogger().info("Learning system initialized and data loaded.");

                // Schedule periodic auto-save (every 10 minutes = 12000 ticks)
                Bukkit.getScheduler().runTaskTimer(plugin, () -> {
                    if (sharedMemoryBank != null && sharedReplayBuffer != null) {
                        LearningSerializer.saveAsync(plugin, sharedMemoryBank, sharedReplayBuffer);
                    }
                }, 12000L, 12000L);
            } else {
                plugin.getLogger().info("Learning system initialized (disabled — no data loaded).");
            }
        } catch (Exception e) {
            plugin.getLogger().log(Level.WARNING, "Failed to initialize learning system. Bots will work without learning.", e);
            this.learningConfig = null;
            this.sharedMemoryBank = null;
            this.sharedReplayBuffer = null;
        }
    }

    public void shutdown() {
        if (sharedMemoryBank != null && sharedReplayBuffer != null) {
            LearningSerializer.saveSynchronous(plugin, sharedMemoryBank, sharedReplayBuffer);
            plugin.getLogger().info("Learning data saved.");
        }
    }

    @Nullable
    public MemoryBank getSharedMemoryBank() {
        return sharedMemoryBank;
    }

    @Nullable
    public ReplayBuffer getSharedReplayBuffer() {
        return sharedReplayBuffer;
    }

    @Nullable
    public LearningConfig getLearningConfig() {
        return learningConfig;
    }
}
