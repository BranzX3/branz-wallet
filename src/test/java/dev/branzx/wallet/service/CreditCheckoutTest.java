package dev.branzx.wallet.service;

import dev.branzx.wallet.api.Checkout;
import dev.branzx.wallet.storage.WalletDatabase;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.UUID;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * A Coin checkout is idempotent even when it spends zero Credits: the immutable
 * ledger row is the replay guard, and the Coin debit is enlisted in the same
 * transaction so the whole thing is all-or-nothing.
 */
class CreditCheckoutTest {

    @TempDir
    Path temp;

    @Test
    void zeroCreditCheckoutIsStillIdempotent() {
        Plugin plugin = mock(Plugin.class);
        FileConfiguration config = mock(FileConfiguration.class);
        when(plugin.getConfig()).thenReturn(config);
        when(plugin.getDataFolder()).thenReturn(temp.toFile());
        when(plugin.getLogger()).thenReturn(Logger.getLogger("CreditCheckoutTest"));
        when(config.getString("storage.type", "sqlite")).thenReturn("sqlite");
        when(config.getLong("credits.coin-offset-per-credit", 20)).thenReturn(20L);
        when(config.getLong("credits.max-offset-percent", 15)).thenReturn(15L);
        when(config.getLong("credits.season-offset-cap", 30_000)).thenReturn(30_000L);

        WalletDatabase database = new WalletDatabase(plugin);
        database.init();
        try {
            UUID owner = UUID.randomUUID();
            CoinService coins = new CoinService(plugin, database);
            assertTrue(coins.add(owner, 1_000));

            CreditService credits = new CreditService(plugin, database, coins, () -> "preseason");

            Checkout first = credits.hybridPay(owner, 100, 0, "warehouse", "checkout-1");
            Checkout replay = credits.hybridPay(owner, 100, 0, "warehouse", "checkout-1");

            assertTrue(first.success());
            assertFalse(replay.success());
            assertEquals(900, coins.balance(owner));
            assertEquals(1, credits.historySync(owner, 10).size());
        } finally {
            database.shutdown();
        }
    }
}
