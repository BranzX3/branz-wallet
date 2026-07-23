package dev.branzx.wallet.service;

import dev.branzx.wallet.api.TopupSettlement;
import dev.branzx.wallet.storage.WalletDatabase;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
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
 * A payment webhook can fire more than once for the same charge, so settling a
 * top-up must grant Credit exactly once and be safe to replay.
 */
class TopupServiceTest {

    @TempDir
    Path temp;

    private WalletDatabase database;
    private CreditService credits;
    private TopupService topups;
    private UUID owner;

    @BeforeEach
    void setUp() {
        Plugin plugin = mock(Plugin.class);
        FileConfiguration config = mock(FileConfiguration.class);
        when(plugin.getConfig()).thenReturn(config);
        when(plugin.getDataFolder()).thenReturn(temp.toFile());
        when(plugin.getLogger()).thenReturn(Logger.getLogger("TopupServiceTest"));
        when(config.getString("storage.type", "sqlite")).thenReturn("sqlite");

        database = new WalletDatabase(plugin);
        database.init();
        CoinService coins = new CoinService(plugin, database);
        credits = new CreditService(plugin, database, coins, () -> "s1");
        topups = new TopupService(plugin, database, credits);
        owner = UUID.randomUUID();
    }

    @AfterEach
    void tearDown() {
        database.shutdown();
    }

    @Test
    void settlingGrantsCreditOnceAndIsReplaySafe() {
        assertTrue(topups.create("ref-1", owner, 115, 10000, "value"));

        assertEquals(TopupSettlement.GRANTED, topups.settle("ref-1", "gbp-abc"));
        assertEquals(115, credits.balance(owner));

        // A replayed webhook must not grant again.
        assertEquals(TopupSettlement.ALREADY_SETTLED, topups.settle("ref-1", "gbp-abc"));
        assertEquals(115, credits.balance(owner));
    }

    @Test
    void unknownReferenceSettlesToNothing() {
        assertEquals(TopupSettlement.UNKNOWN, topups.settle("does-not-exist", null));
        assertEquals(0, credits.balance(owner));
    }

    @Test
    void duplicateReferenceIsRejectedAtCreate() {
        assertTrue(topups.create("ref-dup", owner, 20, 2000, "starter"));
        assertFalse(topups.create("ref-dup", owner, 20, 2000, "starter"));
    }
}
