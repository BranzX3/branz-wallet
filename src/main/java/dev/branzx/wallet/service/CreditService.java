package dev.branzx.wallet.service;

import dev.branzx.wallet.api.Checkout;
import dev.branzx.wallet.api.LedgerEntry;
import dev.branzx.wallet.storage.WalletDatabase;
import org.bukkit.plugin.Plugin;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

/**
 * Integer, non-transferable Credit wallet with an immutable ledger and bounded
 * Hybrid Pay. Ported from Idle's CreditService so the hard-won invariants
 * survive the move to the central plugin.
 *
 * <p><b>The database row is authoritative, never this cache.</b> Credits are
 * bought with real money and may be granted from outside this server — a
 * payment webhook, an admin on a sibling backend — at any moment. So every
 * mutation is a relative UPDATE guarded by its own precondition, and the cached
 * copy is dropped immediately afterwards to be re-read on demand. Writing a
 * whole row back from a snapshot would silently erase an unseen grant: that is
 * paid currency disappearing, so it is worth the extra query to never do it.
 */
public final class CreditService {

    private record Wallet(long credits, String season, long seasonOffset, long seasonCoinsEarned) {
    }

    private static final String SELECT_WALLET =
            "SELECT credits, season_id, season_coin_offset, season_coins_earned "
                    + "FROM wallet_credit WHERE owner_uuid = ?";

    private final Plugin plugin;
    private final WalletDatabase database;
    private final CoinService coins;
    private final Supplier<String> seasonId;
    private final ConcurrentHashMap<UUID, Wallet> wallets = new ConcurrentHashMap<>();

    public CreditService(Plugin plugin, WalletDatabase database, CoinService coins,
                         Supplier<String> seasonId) {
        this.plugin = plugin;
        this.database = database;
        this.coins = coins;
        this.seasonId = seasonId;
    }

    /** Forgets a cached wallet so a later read re-fetches the authoritative row. */
    public void invalidate(UUID owner) {
        wallets.remove(owner);
    }

    public long balance(UUID owner) {
        return current(owner).credits();
    }

    public void recordCoinsEarned(UUID owner, long amount) {
        if (amount <= 0) return;
        String season = seasonId.get();
        database.submitWrite(() -> {
            try (Connection connection = database.getConnection()) {
                ensureRow(connection, owner, season);
                rollSeasonIfStale(connection, owner, season);
                try (PreparedStatement update = connection.prepareStatement(
                        "UPDATE wallet_credit "
                                + "SET season_coins_earned = season_coins_earned + ? "
                                + "WHERE owner_uuid = ?")) {
                    update.setLong(1, amount);
                    update.setString(2, owner.toString());
                    update.executeUpdate();
                }
                invalidate(owner);
            } catch (SQLException e) {
                plugin.getLogger().severe("Failed to record season earnings for "
                        + owner + ": " + e.getMessage());
            }
        });
    }

    /**
     * Idempotent payment-provider/admin adjustment — the entry point the
     * Discord top-up bot uses. Replaying the same transaction id cannot mint
     * Credits twice: {@code transaction_id} is the ledger's primary key, so the
     * duplicate INSERT aborts the whole transaction and nothing is granted.
     */
    public boolean adjust(UUID owner, long amount, String type,
                          String transactionId, String detail) {
        if (transactionId == null || transactionId.isBlank() || amount == 0) return false;
        String season = seasonId.get();
        boolean committed = database.executeTransaction("Credit adjustment " + transactionId, connection -> {
            ensureRow(connection, owner, season);
            try (PreparedStatement insert = connection.prepareStatement(
                    "INSERT INTO wallet_credit_ledger "
                            + "(transaction_id, owner_uuid, entry_type, amount, detail_json) VALUES (?, ?, ?, ?, ?)")) {
                insert.setString(1, transactionId);
                insert.setString(2, owner.toString());
                insert.setString(3, type);
                insert.setLong(4, amount);
                insert.setString(5, detail);
                insert.executeUpdate();
            }
            // The balance floor lives in the WHERE clause so a deduction can
            // never overdraw, however stale this server's view was.
            try (PreparedStatement update = connection.prepareStatement(
                    "UPDATE wallet_credit SET credits = credits + ? "
                            + "WHERE owner_uuid = ? AND credits + ? >= 0")) {
                update.setLong(1, amount);
                update.setString(2, owner.toString());
                update.setLong(3, amount);
                if (update.executeUpdate() != 1) {
                    throw new SQLException("Insufficient Credits or missing wallet row");
                }
            }
        });
        invalidate(owner);
        if (!committed) return false;
        plugin.getLogger().info("CREDIT_" + type + " " + owner + " " + amount
                + " tx=" + transactionId);
        return true;
    }

    /**
     * Pays a qualifying fixed-result Coin checkout. At least 85% remains
     * payable in Coins; seasonal offset is additionally bounded by 30,000 Coins
     * and 25% of Coins earned. The Coin debit runs in the same transaction as
     * the Credit spend, so a checkout is all-or-nothing.
     */
    public Checkout hybridPay(UUID owner, long coinPrice, long requestedCredits,
                              String purchaseId, String idempotencyKey) {
        if (coinPrice <= 0) return Checkout.fail("Invalid checkout price.");
        Wallet wallet = current(owner);
        long ratio = plugin.getConfig().getLong("credits.coin-offset-per-credit", 20);
        long maxPercent = plugin.getConfig().getLong("credits.max-offset-percent", 15);
        long seasonalHardCap = plugin.getConfig().getLong("credits.season-offset-cap", 30_000);
        long byPurchase = coinPrice * maxPercent / 100;
        long byEarned = wallet.seasonCoinsEarned() / 4;
        long seasonRemaining = Math.max(0,
                Math.min(seasonalHardCap, byEarned) - wallet.seasonOffset());
        long maxOffset = Math.min(byPurchase, seasonRemaining);
        long credits = Math.min(Math.max(0, requestedCredits),
                Math.min(wallet.credits(), maxOffset / Math.max(1, ratio)));
        long offset = credits * ratio;
        long coinCost = coinPrice - offset;
        if (coins.balance(owner) < coinCost) return Checkout.fail("Not enough Coins.");
        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            return Checkout.fail("Checkout requires an idempotency key.");
        }

        String tx = "HYBRID:" + idempotencyKey;
        String detail = "{\"purchase\":\"" + safe(purchaseId) + "\",\"coinOffset\":" + offset
                + ",\"coins\":" + coinCost + "}";
        boolean committed = database.executeTransaction("Hybrid checkout " + tx, connection -> {
            // The immutable row is the replay guard even when zero Credits are
            // used: every checkout is idempotent, not only Credit spends.
            try (PreparedStatement insert = connection.prepareStatement(
                    "INSERT INTO wallet_credit_ledger "
                            + "(transaction_id, owner_uuid, entry_type, amount, detail_json) "
                            + "VALUES (?, ?, 'CHECKOUT', ?, ?)")) {
                insert.setString(1, tx);
                insert.setString(2, owner.toString());
                insert.setLong(3, -credits);
                insert.setString(4, detail);
                insert.executeUpdate();
            }
            if (credits > 0) {
                try (PreparedStatement update = connection.prepareStatement(
                        "UPDATE wallet_credit SET credits = credits - ?, "
                                + "season_coin_offset = season_coin_offset + ? "
                                + "WHERE owner_uuid = ? AND credits >= ?")) {
                    update.setLong(1, credits);
                    update.setLong(2, offset);
                    update.setString(3, owner.toString());
                    update.setLong(4, credits);
                    if (update.executeUpdate() != 1) {
                        throw new SQLException("Credit balance changed during checkout");
                    }
                }
            }
            // Coin debit enlisted in the same commit — floor-guarded, so it
            // fails the whole checkout rather than overdrawing.
            coins.addWithin(connection, owner, -coinCost);
        });
        if (!committed) {
            return Checkout.fail("Checkout was already processed or a balance changed.");
        }
        invalidate(owner);
        plugin.getLogger().info("HYBRID_PAY " + owner + " coins=" + coinCost + " credits=" + credits);
        return new Checkout(true, "Paid " + coinCost + " Coins + " + credits + " Credits.", coinCost, credits);
    }

    /**
     * Inserts a ledger row and applies a Credit delta on a caller-supplied
     * connection, so a grant can be enlisted in a larger transaction (e.g. a
     * top-up settlement that also flips the payment row to PAID in the same
     * commit). Idempotent via the ledger primary key and floor-guarded — it
     * throws on a duplicate transaction id or an overdraw so the whole unit
     * rolls back.
     */
    public void applyWithin(Connection connection, UUID owner, long amount,
                            String type, String transactionId, String detail) throws SQLException {
        ensureRow(connection, owner, seasonId.get());
        try (PreparedStatement insert = connection.prepareStatement(
                "INSERT INTO wallet_credit_ledger "
                        + "(transaction_id, owner_uuid, entry_type, amount, detail_json) VALUES (?, ?, ?, ?, ?)")) {
            insert.setString(1, transactionId);
            insert.setString(2, owner.toString());
            insert.setString(3, type);
            insert.setLong(4, amount);
            insert.setString(5, detail);
            insert.executeUpdate();
        }
        try (PreparedStatement update = connection.prepareStatement(
                "UPDATE wallet_credit SET credits = credits + ? "
                        + "WHERE owner_uuid = ? AND credits + ? >= 0")) {
            update.setLong(1, amount);
            update.setString(2, owner.toString());
            update.setLong(3, amount);
            if (update.executeUpdate() != 1) {
                throw new SQLException("Insufficient Credits or missing wallet row");
            }
        }
        invalidate(owner);
    }

    /** Most recent ledger entries for {@code owner}, newest first. */
    public List<LedgerEntry> historySync(UUID owner, int limit) {
        List<LedgerEntry> entries = new ArrayList<>();
        try (Connection connection = database.getConnection();
             PreparedStatement select = connection.prepareStatement(
                     "SELECT transaction_id, entry_type, amount, detail_json, created_at "
                             + "FROM wallet_credit_ledger WHERE owner_uuid = ? "
                             + "ORDER BY created_at DESC LIMIT ?")) {
            select.setString(1, owner.toString());
            select.setInt(2, Math.max(1, Math.min(100, limit)));
            try (ResultSet rs = select.executeQuery()) {
                while (rs.next()) {
                    entries.add(new LedgerEntry(rs.getString("transaction_id"),
                            rs.getString("entry_type"), rs.getLong("amount"),
                            rs.getString("detail_json"), String.valueOf(rs.getTimestamp("created_at"))));
                }
            }
        } catch (SQLException e) {
            plugin.getLogger().severe("Failed to load Credit history: " + e.getMessage());
        }
        return entries;
    }

    // ---- wallet reads ----

    private Wallet current(UUID owner) {
        Wallet cached = wallets.get(owner);
        if (cached != null && cached.season().equals(seasonId.get())) {
            return cached;
        }
        Wallet wallet = loadSync(owner);
        if (!wallet.season().equals(seasonId.get())) {
            wallet = rollSeason(owner);
        }
        wallets.put(owner, wallet);
        return wallet;
    }

    private Wallet loadSync(UUID owner) {
        String season = seasonId.get();
        try (Connection connection = database.getConnection()) {
            Wallet wallet = read(connection, owner);
            if (wallet != null) {
                return wallet;
            }
            ensureRow(connection, owner, season);
            Wallet created = read(connection, owner);
            return created != null ? created : new Wallet(0, season, 0, 0);
        } catch (SQLException e) {
            plugin.getLogger().severe("Failed to load Credit wallet for " + owner + ": " + e.getMessage());
            // A zero wallet fails closed: purchases are refused, nothing granted.
            return new Wallet(0, season, 0, 0);
        }
    }

    private Wallet read(Connection connection, UUID owner) throws SQLException {
        try (PreparedStatement select = connection.prepareStatement(SELECT_WALLET)) {
            select.setString(1, owner.toString());
            try (ResultSet rs = select.executeQuery()) {
                if (!rs.next()) {
                    return null;
                }
                return new Wallet(rs.getLong("credits"), rs.getString("season_id"),
                        rs.getLong("season_coin_offset"), rs.getLong("season_coins_earned"));
            }
        }
    }

    private void ensureRow(Connection connection, UUID owner, String season) throws SQLException {
        try (PreparedStatement insert = connection.prepareStatement(
                (database.isSqlite() ? "INSERT OR IGNORE" : "INSERT IGNORE")
                        + " INTO wallet_credit (owner_uuid, credits, season_id, "
                        + "season_coin_offset, season_coins_earned) VALUES (?, 0, ?, 0, 0)")) {
            insert.setString(1, owner.toString());
            insert.setString(2, season);
            insert.executeUpdate();
        }
    }

    private void rollSeasonIfStale(Connection connection, UUID owner, String season)
            throws SQLException {
        try (PreparedStatement update = connection.prepareStatement(
                "UPDATE wallet_credit SET season_id = ?, season_coin_offset = 0, "
                        + "season_coins_earned = 0 WHERE owner_uuid = ? AND season_id <> ?")) {
            update.setString(1, season);
            update.setString(2, owner.toString());
            update.setString(3, season);
            update.executeUpdate();
        }
    }

    private Wallet rollSeason(UUID owner) {
        String season = seasonId.get();
        database.executeTransaction("Credit season roll " + owner,
                connection -> rollSeasonIfStale(connection, owner, season));
        return loadSync(owner);
    }

    private String safe(String value) {
        return value == null ? "" : value.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}
