package dev.branzx.wallet.event;

import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;

import java.util.UUID;

/**
 * A generic community signal that any plugin can fire and any plugin can react
 * to. It lives in BranzWallet — the library both the game (Idle) and the Discord
 * front-end already depend on — so a game moment can reach Discord without those
 * two plugins depending on each other.
 *
 * <p>Fire one with:
 * <pre>{@code
 * Bukkit.getPluginManager().callEvent(new CommunityNotification(
 *         Kind.STREAK, uuid, name, "🔥 7-day streak!", name + " reached a 7-day streak",
 *         true, false));
 * }</pre>
 * The Discord front-end listens and routes it to a feed channel (broadcast)
 * and/or the player's DMs (dm), resolving the Discord user via the wallet link.
 */
public class CommunityNotification extends Event {

    public enum Kind { RANK_UP, MILESTONE, RARE_DROP, SEASON, PURCHASE, STREAK, CUSTOM }

    private static final HandlerList HANDLERS = new HandlerList();

    private final Kind kind;
    private final UUID player;
    private final String playerName;
    private final String title;
    private final String message;
    private final boolean broadcast;
    private final boolean dm;

    public CommunityNotification(Kind kind, UUID player, String playerName,
                                 String title, String message, boolean broadcast, boolean dm) {
        this.kind = kind;
        this.player = player;
        this.playerName = playerName;
        this.title = title;
        this.message = message;
        this.broadcast = broadcast;
        this.dm = dm;
    }

    public Kind kind() {
        return kind;
    }

    /** The player this is about, or null for a server-wide notice. */
    public UUID player() {
        return player;
    }

    public String playerName() {
        return playerName;
    }

    public String title() {
        return title;
    }

    public String message() {
        return message;
    }

    /** Post to the public feed channel. */
    public boolean broadcast() {
        return broadcast;
    }

    /** DM the player (requires a linked Discord account). */
    public boolean dm() {
        return dm;
    }

    @Override
    public HandlerList getHandlers() {
        return HANDLERS;
    }

    public static HandlerList getHandlerList() {
        return HANDLERS;
    }
}
