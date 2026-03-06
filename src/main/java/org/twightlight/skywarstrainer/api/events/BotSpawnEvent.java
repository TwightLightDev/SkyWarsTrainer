package org.twightlight.skywarstrainer.api.events;

import org.bukkit.Location;
import org.bukkit.event.Cancellable;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;
import org.twightlight.skywarstrainer.bot.TrainerBot;

import javax.annotation.Nonnull;

/**
 * Fired when a trainer bot is about to spawn. Cancellable.
 */
public class BotSpawnEvent extends Event implements Cancellable {

    private static final HandlerList HANDLERS = new HandlerList();
    private final TrainerBot bot;
    private final Location location;
    private boolean cancelled;

    public BotSpawnEvent(@Nonnull TrainerBot bot, @Nonnull Location location) {
        this.bot = bot;
        this.location = location;
        this.cancelled = false;
    }

    @Nonnull
    public TrainerBot getBot() { return bot; }

    @Nonnull
    public Location getLocation() { return location; }

    @Override
    public boolean isCancelled() { return cancelled; }

    @Override
    public void setCancelled(boolean cancel) { this.cancelled = cancel; }

    @Override
    @Nonnull
    public HandlerList getHandlers() { return HANDLERS; }

    @Nonnull
    public static HandlerList getHandlerList() { return HANDLERS; }
}
