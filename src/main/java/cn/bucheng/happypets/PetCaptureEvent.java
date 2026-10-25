package cn.bucheng.happypets;

import org.bukkit.entity.Mob;
import org.bukkit.entity.Player;
import org.bukkit.event.Cancellable;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;

/** Fired before consuming food; protection plugins may cancel pet capture. */
public final class PetCaptureEvent extends Event implements Cancellable {
    private static final HandlerList HANDLERS = new HandlerList();
    private final Player player;
    private final Mob entity;
    private boolean cancelled;
    public PetCaptureEvent(Player player, Mob entity) { this.player = player; this.entity = entity; }
    public Player getPlayer() { return player; }
    public Mob getEntity() { return entity; }
    @Override public boolean isCancelled() { return cancelled; }
    @Override public void setCancelled(boolean value) { cancelled = value; }
    @Override public HandlerList getHandlers() { return HANDLERS; }
    public static HandlerList getHandlerList() { return HANDLERS; }
}
