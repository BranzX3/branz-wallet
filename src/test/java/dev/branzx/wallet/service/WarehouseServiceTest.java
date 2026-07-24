package dev.branzx.wallet.service;

import dev.branzx.wallet.storage.WalletDatabase;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * The warehouse is the bridge between two servers, so the property that matters
 * is that concurrent movements commute: a deposit made by one system and a
 * withdrawal made by another must both land, never overwrite each other.
 */
class WarehouseServiceTest {

    @TempDir
    Path temp;

    private WalletDatabase database;
    private WarehouseService warehouse;
    private UUID owner;

    @BeforeEach
    void setUp() {
        Plugin plugin = mock(Plugin.class);
        FileConfiguration config = mock(FileConfiguration.class);
        when(plugin.getConfig()).thenReturn(config);
        when(plugin.getDataFolder()).thenReturn(temp.toFile());
        when(plugin.getLogger()).thenReturn(Logger.getLogger("WarehouseServiceTest"));
        when(config.getString("storage.type", "sqlite")).thenReturn("sqlite");

        database = new WalletDatabase(plugin);
        database.init();
        warehouse = new WarehouseService(plugin, database);
        owner = UUID.randomUUID();
    }

    @AfterEach
    void tearDown() {
        database.shutdown();
    }

    @Test
    void depositsAccumulateAndWithdrawalsSubtract() {
        assertTrue(warehouse.deposit(owner, "oak_log", 100));
        assertTrue(warehouse.deposit(owner, "OAK_LOG", 40));
        assertEquals(140, warehouse.amount(owner, "OAK_LOG"));

        assertTrue(warehouse.withdraw(owner, "OAK_LOG", 60));
        assertEquals(80, warehouse.amount(owner, "OAK_LOG"));
    }

    @Test
    void withdrawWillNotGoNegative() {
        warehouse.deposit(owner, "IRON_INGOT", 10);
        assertFalse(warehouse.withdraw(owner, "IRON_INGOT", 11));
        // The refusal is part of the UPDATE, so nothing was taken on the way.
        assertEquals(10, warehouse.amount(owner, "IRON_INGOT"));
    }

    @Test
    void movementsOnDifferentMaterialsDoNotDisturbEachOther() {
        warehouse.deposit(owner, "OAK_LOG", 50);
        warehouse.deposit(owner, "DIAMOND", 3);

        // A production server topping up one material must not erase another
        // that a combat server spent in the meantime.
        warehouse.withdraw(owner, "DIAMOND", 3);
        warehouse.deposit(owner, "OAK_LOG", 25);

        Map<String, Integer> contents = warehouse.contents(owner);
        assertEquals(75, contents.get("OAK_LOG"));
        // Fully spent materials drop out of the listing rather than sitting at 0.
        assertFalse(contents.containsKey("DIAMOND"));
    }

    @Test
    void bundleAppliesEveryDeltaInOneTransaction() {
        warehouse.deposit(owner, "STONE", 500);
        Map<String, Integer> deltas = new LinkedHashMap<>();
        deltas.put("STONE", -200);
        deltas.put("COAL", 64);

        assertTrue(database.executeTransaction("bundle",
                connection -> warehouse.applyWithin(connection, owner, deltas)));

        assertEquals(300, warehouse.amount(owner, "STONE"));
        assertEquals(64, warehouse.amount(owner, "COAL"));
    }

    @Test
    void anOverdrawnBundleRollsBackWholesale() {
        warehouse.deposit(owner, "STONE", 10);
        Map<String, Integer> deltas = new LinkedHashMap<>();
        deltas.put("COAL", 32);
        deltas.put("STONE", -50);   // more than is held — must sink the bundle

        assertFalse(database.executeTransaction("bad bundle",
                connection -> warehouse.applyWithin(connection, owner, deltas)));

        assertEquals(10, warehouse.amount(owner, "STONE"));
        assertEquals(0, warehouse.amount(owner, "COAL"));
    }
}
