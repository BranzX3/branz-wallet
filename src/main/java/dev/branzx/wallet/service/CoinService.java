package dev.branzx.wallet.service;

import dev.branzx.wallet.api.LeaderEntry;
import dev.branzx.wallet.storage.WalletDatabase;
import org.bukkit.plugin.Plugin;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * The MMO Coin balance. The shared database row is authoritative: Coins are
 * earned and spent across every backend, so every mutation is a relative,
 * floor-guarded UPDATE ({@code coins = coins - ? WHERE coins >= ?}) rather than
 * a read-modify-write of a cached snapshot. That is exactly what lets a debit
 * be enlisted inside another plugin's own transaction (e.g. Idle charging for a
 * claim in the same commit that writes the node row) without losing atomicity.
 */
public final class CoinService {

    private final Plugin plugin;
    private final WalletDatabase database;

    public CoinService(Plugin plugin, WalletDatabase database) {
        this.plugin = plugin;
        this.database = database;
    }

    /** Ensures an account row exists so later relative updates have a target. */
    public void ensureAccount(UUID owner, String name) {
        database.submitWrite(() -> {
            try (Connection connection = database.getConnection()) {
                ensureRow(connection, owner, name);
            } catch (SQLException e) {
                plugin.getLogger().severe("Failed to create Coin account for "
                        + owner + ": " + e.getMessage());
            }
        });
    }

    public long balance(UUID owner) {
        try (Connection connection = database.getConnection();
             PreparedStatement select = connection.prepareStatement(
                     "SELECT coins FROM wallet_accounts WHERE uuid = ?")) {
            select.setString(1, owner.toString());
            try (ResultSet rs = select.executeQuery()) {
                return rs.next() ? rs.getLong("coins") : 0;
            }
        } catch (SQLException e) {
            plugin.getLogger().severe("Failed to read Coin balance for " + owner + ": " + e.getMessage());
            return 0;
        }
    }

    /**
     * Adds {@code amount} Coins (negative to debit). Returns false when a debit
     * would overdraw. Blocking so callers (e.g. a Vault withdraw) learn the
     * outcome; safe to race with other backends because the floor is in the
     * WHERE clause.
     */
    public boolean add(UUID owner, long amount) {
        if (amount == 0) return true;
        return database.executeTransaction("Coin adjust " + owner, connection -> {
            ensureRow(connection, owner, null);
            addWithin(connection, owner, amount);
        });
    }

    /**
     * The same floor-guarded debit/credit, but run against a caller-supplied
     * connection so it can be part of a larger gameplay transaction. Throws on
     * overdraw to roll the whole unit back.
     */
    public void addWithin(Connection connection, UUID owner, long amount) throws SQLException {
        try (PreparedStatement update = connection.prepareStatement(
                "UPDATE wallet_accounts SET coins = coins + ? WHERE uuid = ? AND coins + ? >= 0")) {
            update.setLong(1, amount);
            update.setString(2, owner.toString());
            update.setLong(3, amount);
            if (update.executeUpdate() != 1) {
                throw new SQLException("Insufficient Coins or missing account row");
            }
        }
    }

    /** Top {@code limit} accounts by Coin balance, highest first. */
    public List<LeaderEntry> top(int limit) {
        List<LeaderEntry> out = new ArrayList<>();
        try (Connection connection = database.getConnection();
             PreparedStatement select = connection.prepareStatement(
                     "SELECT uuid, name, coins FROM wallet_accounts ORDER BY coins DESC LIMIT ?")) {
            select.setInt(1, Math.max(1, Math.min(50, limit)));
            try (ResultSet rs = select.executeQuery()) {
                while (rs.next()) {
                    out.add(new LeaderEntry(UUID.fromString(rs.getString("uuid")),
                            rs.getString("name"), rs.getLong("coins")));
                }
            }
        } catch (SQLException e) {
            plugin.getLogger().severe("Failed to read Coin leaderboard: " + e.getMessage());
        }
        return out;
    }

    private void ensureRow(Connection connection, UUID owner, String name) throws SQLException {
        try (PreparedStatement insert = connection.prepareStatement(
                (database.isSqlite() ? "INSERT OR IGNORE" : "INSERT IGNORE")
                        + " INTO wallet_accounts (uuid, name, coins) VALUES (?, ?, 0)")) {
            insert.setString(1, owner.toString());
            insert.setString(2, name == null ? owner.toString().substring(0, 16) : name);
            insert.executeUpdate();
        }
    }
}
