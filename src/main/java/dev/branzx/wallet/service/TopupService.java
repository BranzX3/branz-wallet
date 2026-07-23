package dev.branzx.wallet.service;

import dev.branzx.wallet.api.TopupInfo;
import dev.branzx.wallet.api.TopupSettlement;
import dev.branzx.wallet.storage.WalletDatabase;
import org.bukkit.plugin.Plugin;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.UUID;

/**
 * Real-money top-up orders. A pending row is created when the buyer starts a
 * checkout; a payment webhook later settles it. Settlement is idempotent and
 * atomic: the order flips to PAID and the Credit is granted in one commit, and
 * the ledger primary key ({@code reference}) plus the PENDING status guard mean
 * a replayed webhook can never grant Credit twice.
 */
public final class TopupService {

    private final Plugin plugin;
    private final WalletDatabase database;
    private final CreditService credits;

    public TopupService(Plugin plugin, WalletDatabase database, CreditService credits) {
        this.plugin = plugin;
        this.database = database;
        this.credits = credits;
    }

    /** Records a pending order. Returns false on bad input or a duplicate reference. */
    public boolean create(String reference, UUID owner, long creditAmount,
                          long amountSatang, String packageId) {
        if (reference == null || reference.isBlank() || owner == null || creditAmount <= 0) {
            return false;
        }
        return database.executeTransaction("Topup create " + reference, connection -> {
            try (PreparedStatement insert = connection.prepareStatement(
                    "INSERT INTO wallet_topup "
                            + "(reference, owner_uuid, credits, amount_satang, package_id, status) "
                            + "VALUES (?, ?, ?, ?, ?, 'PENDING')")) {
                insert.setString(1, reference);
                insert.setString(2, owner.toString());
                insert.setLong(3, creditAmount);
                insert.setLong(4, amountSatang);
                insert.setString(5, packageId);
                insert.executeUpdate();
            }
        });
    }

    /**
     * Settles an order: marks it PAID and grants the Credit in one transaction.
     * Safe to call repeatedly for the same reference (webhooks retry) — a row
     * that is already PAID returns {@link TopupSettlement#ALREADY_SETTLED}
     * without granting again.
     */
    public TopupSettlement settle(String reference, String providerRef) {
        if (reference == null || reference.isBlank()) {
            return TopupSettlement.UNKNOWN;
        }
        TopupSettlement[] result = {TopupSettlement.UNKNOWN};
        boolean committed = database.executeTransaction("Topup settle " + reference, connection -> {
            UUID owner;
            long creditAmount;
            String status;
            try (PreparedStatement select = connection.prepareStatement(
                    "SELECT owner_uuid, credits, status FROM wallet_topup WHERE reference = ?")) {
                select.setString(1, reference);
                try (ResultSet rs = select.executeQuery()) {
                    if (!rs.next()) {
                        result[0] = TopupSettlement.UNKNOWN;
                        return;
                    }
                    owner = UUID.fromString(rs.getString("owner_uuid"));
                    creditAmount = rs.getLong("credits");
                    status = rs.getString("status");
                }
            }
            if (!"PENDING".equals(status)) {
                result[0] = TopupSettlement.ALREADY_SETTLED;
                return;
            }
            try (PreparedStatement update = connection.prepareStatement(
                    "UPDATE wallet_topup SET status = 'PAID', provider_ref = ? "
                            + "WHERE reference = ? AND status = 'PENDING'")) {
                update.setString(1, providerRef);
                update.setString(2, reference);
                if (update.executeUpdate() != 1) {
                    throw new SQLException("Top-up row changed during settlement");
                }
            }
            // Ledger transaction_id = reference, so this grant is the same
            // idempotent write the Discord bot's adjustCredit would do.
            credits.applyWithin(connection, owner, creditAmount, "TOPUP", reference,
                    "{\"package\":\"" + safe(reference) + "\"}");
            result[0] = TopupSettlement.GRANTED;
        });
        if (!committed) {
            plugin.getLogger().warning("Top-up settlement did not commit for " + reference);
            return TopupSettlement.UNKNOWN;
        }
        return result[0];
    }

    public TopupInfo get(String reference) {
        try (Connection connection = database.getConnection();
             PreparedStatement select = connection.prepareStatement(
                     "SELECT owner_uuid, credits, amount_satang, package_id, status "
                             + "FROM wallet_topup WHERE reference = ?")) {
            select.setString(1, reference);
            try (ResultSet rs = select.executeQuery()) {
                if (!rs.next()) {
                    return null;
                }
                return new TopupInfo(reference, UUID.fromString(rs.getString("owner_uuid")),
                        rs.getLong("credits"), rs.getLong("amount_satang"),
                        rs.getString("package_id"), rs.getString("status"));
            }
        } catch (SQLException e) {
            plugin.getLogger().severe("Failed to read top-up " + reference + ": " + e.getMessage());
            return null;
        }
    }

    private String safe(String value) {
        return value == null ? "" : value.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}
