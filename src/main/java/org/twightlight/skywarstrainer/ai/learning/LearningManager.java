package org.twightlight.skywarstrainer.ai.learning;

import org.bukkit.Bukkit;
import org.twightlight.skywarstrainer.SkyWarsTrainer;

import javax.annotation.Nullable;
import java.util.logging.Level;

public class LearningManager {
    private SkyWarsTrainer plugin;

    private LearningConfig learningConfig;
    private MemoryBank sharedMemoryBank;
    private ReplayBuffer sharedReplayBuffer;

    public LearningManager(SkyWarsTrainer plugin) {
        this.plugin = plugin;
        try {
            this.learningConfig = new LearningConfig(plugin);
            this.sharedMemoryBank = new MemoryBank(learningConfig);
            this.sharedReplayBuffer = new ReplayBuffer(learningConfig);
            LearningSerializer.load(plugin, sharedMemoryBank, sharedReplayBuffer);
            plugin.getLogger().info("Learning system initialized.");

            // 5c. Schedule periodic auto-save (every 10 minutes = 12000 ticks)
            if (learningConfig.isEnabled()) {
                Bukkit.getScheduler().runTaskTimer(plugin, new Runnable() {
                    @Override
                    public void run() {
                        if (sharedMemoryBank != null && sharedReplayBuffer != null) {
                            LearningSerializer.saveAsync(plugin, sharedMemoryBank, sharedReplayBuffer);
                        }
                    }
                }, 12000L, 12000L);
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
