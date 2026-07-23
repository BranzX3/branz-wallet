package dev.branzx.wallet.service;

import dev.branzx.wallet.storage.WalletDatabase;
import org.bukkit.plugin.Plugin;

import java.security.SecureRandom;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.UUID;

/**
 * Binds a Discord account to a Minecraft one. A player runs {@code /wallet link}
 * in-game to mint a short-lived 6-digit code; they then type it to the Discord
 * bot, which calls {@link #redeem}. The code proves account ownership without
 * ever handling a password.
 */
public final class LinkService {

    private static final SecureRandom RANDOM = new SecureRandom();

    private final Plugin plugin;
    private final WalletDatabase database;

    public LinkService(Plugin plugin, WalletDatabase database) {
        this.plugin = plugin;
        this.database = database;
    }

    /** Mints (or replaces) a 6-digit code for {@code owner}. Returns the code. */
    public String mintCode(UUID owner) {
        String code = String.format("%06d", RANDOM.nextInt(1_000_000));
        long ttlMinutes = plugin.getConfig().getLong("link.code-ttl-minutes", 5);
        long expiresAt = System.currentTimeMillis() + ttlMinutes * 60_000L;
        database.executeTransaction("Mint link code " + owner, connection -> {
            try (PreparedStatement delete = connection.prepareStatement(
                    "DELETE FROM wallet_link_code WHERE owner_uuid = ?")) {
                delete.setString(1, owner.toString());
                delete.executeUpdate();
            }
            try (PreparedStatement insert = connection.prepareStatement(
                    "INSERT INTO wallet_link_code (code, owner_uuid, expires_at) VALUES (?, ?, ?)")) {
                insert.setString(1, code);
                insert.setString(2, owner.toString());
                insert.setLong(3, expiresAt);
                insert.executeUpdate();
            }
        });
        return code;
    }

    /**
     * Consumes {@code code} for {@code discordId}. Returns the linked UUID, or
     * null if the code is unknown, expired, or already used. Deleting the code
     * inside the same transaction makes redemption single-use.
     */
    public UUID redeem(String code, String discordId) {
        if (code == null || discordId == null) return null;
        UUID[] result = new UUID[1];
        boolean committed = database.executeTransaction("Redeem link " + discordId, connection -> {
            UUID owner = null;
            try (PreparedStatement select = connection.prepareStatement(
                    "SELECT owner_uuid, expires_at FROM wallet_link_code WHERE code = ?")) {
                select.setString(1, code.trim());
                try (ResultSet rs = select.executeQuery()) {
                    if (rs.next() && rs.getLong("expires_at") >= System.currentTimeMillis()) {
                        owner = UUID.fromString(rs.getString("owner_uuid"));
                    }
                }
            }
            if (owner == null) {
                throw new SQLException("Unknown or expired code");
            }
            try (PreparedStatement delete = connection.prepareStatement(
                    "DELETE FROM wallet_link_code WHERE code = ?")) {
                delete.setString(1, code.trim());
                delete.executeUpdate();
            }
            try (PreparedStatement upsert = connection.prepareStatement(
                    (database.isSqlite() ? "INSERT OR REPLACE" : "REPLACE")
                            + " INTO wallet_discord_link (discord_id, owner_uuid) VALUES (?, ?)")) {
                upsert.setString(1, discordId);
                upsert.setString(2, owner.toString());
                upsert.executeUpdate();
            }
            result[0] = owner;
        });
        return committed ? result[0] : null;
    }

    /** Minecraft UUID currently linked to {@code discordId}, or null. */
    public UUID linkedUuid(String discordId) {
        try (Connection connection = database.getConnection();
             PreparedStatement select = connection.prepareStatement(
                     "SELECT owner_uuid FROM wallet_discord_link WHERE discord_id = ?")) {
            select.setString(1, discordId);
            try (ResultSet rs = select.executeQuery()) {
                return rs.next() ? UUID.fromString(rs.getString("owner_uuid")) : null;
            }
        } catch (SQLException e) {
            plugin.getLogger().severe("Failed to read Discord link for " + discordId + ": " + e.getMessage());
            return null;
        }
    }
}
