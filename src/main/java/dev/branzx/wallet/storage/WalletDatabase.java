package dev.branzx.wallet.storage;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.plugin.Plugin;

import java.io.File;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

/**
 * Owns the shared connection pool and the wallet schema. Mirrors the proven
 * ordering/durability model from Idle's Database: a single-threaded writer
 * keeps DB writes off the main thread while preserving submission order, and
 * caches (not this class) are the runtime authority.
 */
public final class WalletDatabase {

    @FunctionalInterface
    public interface TransactionWork {
        void execute(Connection connection) throws Exception;
    }

    private final Plugin plugin;
    private HikariDataSource dataSource;
    private boolean sqlite;
    private ExecutorService writeQueue;

    public WalletDatabase(Plugin plugin) {
        this.plugin = plugin;
    }

    public boolean isSqlite() {
        return sqlite;
    }

    public void init() {
        String type = plugin.getConfig().getString("storage.type", "sqlite")
                .toLowerCase(Locale.ROOT);
        this.sqlite = !type.equals("mysql");

        HikariConfig hikariConfig = new HikariConfig();
        hikariConfig.setPoolName("Wallet-Pool");

        if (sqlite) {
            File dbFile = new File(plugin.getDataFolder(), "wallet.db");
            plugin.getDataFolder().mkdirs();
            hikariConfig.setJdbcUrl("jdbc:sqlite:" + dbFile.getAbsolutePath());
            hikariConfig.setMaximumPoolSize(1);
        } else {
            ConfigurationSection cfg = plugin.getConfig().getConfigurationSection("mysql");
            String host = cfg.getString("host", "localhost");
            int port = cfg.getInt("port", 3306);
            String database = cfg.getString("database", "idle");
            boolean useSSL = cfg.getBoolean("useSSL", false);
            boolean allowPublicKeyRetrieval = cfg.getBoolean("allow-public-key-retrieval", false);
            hikariConfig.setJdbcUrl("jdbc:mysql://" + host + ":" + port + "/" + database
                    + "?useSSL=" + useSSL + "&autoReconnect=true&characterEncoding=utf8"
                    + "&allowPublicKeyRetrieval=" + allowPublicKeyRetrieval);
            hikariConfig.setUsername(cfg.getString("username", "root"));
            hikariConfig.setPassword(cfg.getString("password", ""));
            hikariConfig.setMaximumPoolSize(cfg.getInt("pool-size", 10));
        }

        this.dataSource = new HikariDataSource(hikariConfig);
        this.writeQueue = Executors.newSingleThreadExecutor(r -> {
            Thread thread = new Thread(r, "Wallet-DB-Writer");
            thread.setDaemon(false);
            return thread;
        });
        createSchema();
        plugin.getLogger().info("Storage: " + (sqlite ? "SQLite (wallet.db)" : "MySQL"));
    }

    public Connection getConnection() throws SQLException {
        return dataSource.getConnection();
    }

    /** Queue a DB write to run off the main thread, in submission order. */
    public void submitWrite(Runnable write) {
        writeQueue.submit(() -> {
            try {
                write.run();
            } catch (Exception e) {
                plugin.getLogger().severe("Queued DB write failed: " + e.getMessage());
            }
        });
    }

    /**
     * Ordered blocking transaction for actions that must know whether the
     * durable commit succeeded before granting an entitlement (checkout, admin
     * settlement, payment webhook). Keep these rare — the caller waits for the
     * writer.
     */
    public boolean executeTransaction(String operation, TransactionWork work) {
        if (Thread.currentThread().getName().equals("Wallet-DB-Writer")) {
            throw new IllegalStateException(
                    "Blocking transaction cannot be nested on the DB writer: " + operation);
        }
        Future<?> future = writeQueue.submit(() -> runTransaction(operation, work));
        try {
            future.get();
            return true;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        } catch (java.util.concurrent.ExecutionException e) {
            plugin.getLogger().warning(operation + " was rejected: "
                    + e.getCause().getMessage());
            return false;
        }
    }

    private void runTransaction(String operation, TransactionWork work) {
        try (Connection connection = getConnection()) {
            connection.setAutoCommit(false);
            try {
                work.execute(connection);
                connection.commit();
            } catch (Exception e) {
                try {
                    connection.rollback();
                } catch (SQLException rollbackFailure) {
                    e.addSuppressed(rollbackFailure);
                }
                throw new IllegalStateException(operation + " transaction failed", e);
            } finally {
                try {
                    connection.setAutoCommit(true);
                } catch (SQLException ignored) {
                    // The pooled connection is closing immediately.
                }
            }
        } catch (SQLException e) {
            throw new IllegalStateException(operation + " could not acquire a connection", e);
        }
    }

    private void createSchema() {
        String[] ddl = sqlite ? SQLITE_DDL : MYSQL_DDL;
        try (Connection connection = getConnection(); Statement statement = connection.createStatement()) {
            for (String sql : ddl) {
                statement.execute(sql);
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to create wallet schema", e);
        }
    }

    public void shutdown() {
        if (writeQueue != null) {
            writeQueue.shutdown();
            try {
                if (!writeQueue.awaitTermination(10, TimeUnit.SECONDS)) {
                    writeQueue.shutdownNow();
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                writeQueue.shutdownNow();
            }
        }
        if (dataSource != null) {
            dataSource.close();
        }
    }

    // ---- schema ----
    //
    // wallet_accounts   : one row per player; the Coin balance (MMO currency).
    // wallet_credit     : premium Credit balance + seasonal offset accounting.
    // wallet_credit_ledger : immutable, idempotent record of every Credit move.
    // wallet_link_code  : short-lived 6-digit codes minted in-game for Discord.
    // wallet_discord_link : the durable Discord<->Minecraft binding.

    private static final String[] MYSQL_DDL = {
            """
            CREATE TABLE IF NOT EXISTS wallet_accounts (
                uuid VARCHAR(36) NOT NULL PRIMARY KEY,
                name VARCHAR(16) NOT NULL,
                coins BIGINT NOT NULL DEFAULT 0
            )
            """,
            """
            CREATE TABLE IF NOT EXISTS wallet_credit (
                owner_uuid VARCHAR(36) NOT NULL PRIMARY KEY,
                credits BIGINT NOT NULL DEFAULT 0,
                season_id VARCHAR(32) NOT NULL DEFAULT 'preseason',
                season_coin_offset BIGINT NOT NULL DEFAULT 0,
                season_coins_earned BIGINT NOT NULL DEFAULT 0
            )
            """,
            """
            CREATE TABLE IF NOT EXISTS wallet_credit_ledger (
                transaction_id VARCHAR(64) NOT NULL PRIMARY KEY,
                owner_uuid VARCHAR(36) NOT NULL,
                entry_type VARCHAR(24) NOT NULL,
                amount BIGINT NOT NULL,
                detail_json TEXT,
                created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
                KEY idx_credit_owner (owner_uuid)
            )
            """,
            """
            CREATE TABLE IF NOT EXISTS wallet_link_code (
                code VARCHAR(12) NOT NULL PRIMARY KEY,
                owner_uuid VARCHAR(36) NOT NULL,
                expires_at BIGINT NOT NULL
            )
            """,
            """
            CREATE TABLE IF NOT EXISTS wallet_discord_link (
                discord_id VARCHAR(32) NOT NULL PRIMARY KEY,
                owner_uuid VARCHAR(36) NOT NULL UNIQUE,
                linked_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
            )
            """,
            """
            CREATE TABLE IF NOT EXISTS wallet_topup (
                reference VARCHAR(64) NOT NULL PRIMARY KEY,
                owner_uuid VARCHAR(36) NOT NULL,
                credits BIGINT NOT NULL,
                amount_satang BIGINT NOT NULL,
                package_id VARCHAR(32),
                status VARCHAR(16) NOT NULL DEFAULT 'PENDING',
                provider_ref VARCHAR(64),
                created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
                KEY idx_topup_owner (owner_uuid)
            )
            """,
    };

    private static final String[] SQLITE_DDL = {
            """
            CREATE TABLE IF NOT EXISTS wallet_accounts (
                uuid TEXT NOT NULL PRIMARY KEY,
                name TEXT NOT NULL,
                coins INTEGER NOT NULL DEFAULT 0
            )
            """,
            """
            CREATE TABLE IF NOT EXISTS wallet_credit (
                owner_uuid TEXT NOT NULL PRIMARY KEY,
                credits INTEGER NOT NULL DEFAULT 0,
                season_id TEXT NOT NULL DEFAULT 'preseason',
                season_coin_offset INTEGER NOT NULL DEFAULT 0,
                season_coins_earned INTEGER NOT NULL DEFAULT 0
            )
            """,
            """
            CREATE TABLE IF NOT EXISTS wallet_credit_ledger (
                transaction_id TEXT NOT NULL PRIMARY KEY,
                owner_uuid TEXT NOT NULL,
                entry_type TEXT NOT NULL,
                amount INTEGER NOT NULL,
                detail_json TEXT,
                created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
            )
            """,
            "CREATE INDEX IF NOT EXISTS idx_credit_owner ON wallet_credit_ledger (owner_uuid)",
            """
            CREATE TABLE IF NOT EXISTS wallet_link_code (
                code TEXT NOT NULL PRIMARY KEY,
                owner_uuid TEXT NOT NULL,
                expires_at INTEGER NOT NULL
            )
            """,
            """
            CREATE TABLE IF NOT EXISTS wallet_discord_link (
                discord_id TEXT NOT NULL PRIMARY KEY,
                owner_uuid TEXT NOT NULL UNIQUE,
                linked_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
            )
            """,
            """
            CREATE TABLE IF NOT EXISTS wallet_topup (
                reference TEXT NOT NULL PRIMARY KEY,
                owner_uuid TEXT NOT NULL,
                credits INTEGER NOT NULL,
                amount_satang INTEGER NOT NULL,
                package_id TEXT,
                status TEXT NOT NULL DEFAULT 'PENDING',
                provider_ref TEXT,
                created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
            )
            """,
    };
}
