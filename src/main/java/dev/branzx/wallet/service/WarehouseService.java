package dev.branzx.wallet.service;

import dev.branzx.wallet.storage.WalletDatabase;
import org.bukkit.plugin.Plugin;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * The shared material warehouse — the bridge that lets one system produce a
 * resource and another consume it. Rows are per material, so a deposit made on
 * the production server and a withdrawal made on the combat server commute
 * instead of overwriting each other; there is deliberately no serialised
 * "whole inventory" write anywhere.
 *
 * <p>Quantities only. Capacity, pools and any game rules about what may be
 * stored stay with the system that owns those rules.
 */
public final class WarehouseService {

    private final Plugin plugin;
    private final WalletDatabase database;

    public WarehouseService(Plugin plugin, WalletDatabase database) {
        this.plugin = plugin;
        this.database = database;
    }

    /** Everything {@code owner} is holding. Empty when they hold nothing. */
    public Map<String, Integer> contents(UUID owner) {
        Map<String, Integer> out = new LinkedHashMap<>();
        try (Connection connection = database.getConnection();
             PreparedStatement select = connection.prepareStatement(
                     "SELECT item_key, amount FROM wallet_warehouse "
                             + "WHERE owner_uuid = ? AND amount > 0 ORDER BY item_key")) {
            select.setString(1, owner.toString());
            try (ResultSet rs = select.executeQuery()) {
                while (rs.next()) {
                    out.put(rs.getString("item_key"), rs.getInt("amount"));
                }
            }
        } catch (SQLException e) {
            plugin.getLogger().severe("Failed to read warehouse for " + owner + ": " + e.getMessage());
        }
        return out;
    }

    /** How much of one material {@code owner} holds. */
    public int amount(UUID owner, String itemKey) {
        try (Connection connection = database.getConnection();
             PreparedStatement select = connection.prepareStatement(
                     "SELECT amount FROM wallet_warehouse WHERE owner_uuid = ? AND item_key = ?")) {
            select.setString(1, owner.toString());
            select.setString(2, key(itemKey));
            try (ResultSet rs = select.executeQuery()) {
                return rs.next() ? rs.getInt("amount") : 0;
            }
        } catch (SQLException e) {
            plugin.getLogger().severe("Failed to read warehouse item for " + owner + ": " + e.getMessage());
            return 0;
        }
    }

    /** Adds {@code amount} of one material. Blocking; reports whether it committed. */
    public boolean deposit(UUID owner, String itemKey, int amount) {
        if (amount <= 0) {
            return false;
        }
        return database.executeTransaction("Warehouse deposit " + owner,
                connection -> creditWithin(connection, owner, itemKey, amount));
    }

    /**
     * Removes {@code amount} of one material, refusing to go negative. The
     * sufficiency check is part of the UPDATE, so two servers spending the same
     * stack cannot both succeed.
     */
    public boolean withdraw(UUID owner, String itemKey, int amount) {
        if (amount <= 0) {
            return false;
        }
        return database.executeTransaction("Warehouse withdraw " + owner,
                connection -> debitWithin(connection, owner, itemKey, amount));
    }

    /**
     * Applies a whole bundle of signed deltas on a caller-supplied connection,
     * so a warehouse movement can commit inside the transaction that settles
     * whatever produced or consumed it. Throws on an overdraw, rolling the
     * caller's unit back with it.
     */
    public void applyWithin(Connection connection, UUID owner, Map<String, Integer> deltas)
            throws SQLException {
        if (deltas == null || deltas.isEmpty()) {
            return;
        }
        for (Map.Entry<String, Integer> entry : deltas.entrySet()) {
            int delta = entry.getValue() == null ? 0 : entry.getValue();
            if (delta > 0) {
                creditWithin(connection, owner, entry.getKey(), delta);
            } else if (delta < 0) {
                debitWithin(connection, owner, entry.getKey(), -delta);
            }
        }
    }

    /** Relative add; creates the row on first sight of the material. */
    public void creditWithin(Connection connection, UUID owner, String itemKey, int amount)
            throws SQLException {
        if (amount <= 0) {
            return;
        }
        String sql = database.isSqlite()
                ? "INSERT INTO wallet_warehouse (owner_uuid, item_key, amount) VALUES (?, ?, ?) "
                        + "ON CONFLICT(owner_uuid, item_key) DO UPDATE SET amount = amount + ?"
                : "INSERT INTO wallet_warehouse (owner_uuid, item_key, amount) VALUES (?, ?, ?) "
                        + "ON DUPLICATE KEY UPDATE amount = amount + ?";
        try (PreparedStatement upsert = connection.prepareStatement(sql)) {
            upsert.setString(1, owner.toString());
            upsert.setString(2, key(itemKey));
            upsert.setInt(3, amount);
            upsert.setInt(4, amount);
            upsert.executeUpdate();
        }
    }

    /** Relative subtract with the floor in the WHERE clause. */
    public void debitWithin(Connection connection, UUID owner, String itemKey, int amount)
            throws SQLException {
        if (amount <= 0) {
            return;
        }
        try (PreparedStatement update = connection.prepareStatement(
                "UPDATE wallet_warehouse SET amount = amount - ? "
                        + "WHERE owner_uuid = ? AND item_key = ? AND amount >= ?")) {
            update.setInt(1, amount);
            update.setString(2, owner.toString());
            update.setString(3, key(itemKey));
            update.setInt(4, amount);
            if (update.executeUpdate() != 1) {
                throw new SQLException("Not enough " + itemKey + " in the warehouse of " + owner);
            }
        }
    }

    private static String key(String itemKey) {
        return itemKey == null ? "" : itemKey.toUpperCase(java.util.Locale.ROOT);
    }
}
