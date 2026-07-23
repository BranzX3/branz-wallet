package dev.branzx.wallet;

import dev.branzx.wallet.api.WalletApi;
import dev.branzx.wallet.economy.WalletEconomy;
import dev.branzx.wallet.service.CoinService;
import dev.branzx.wallet.service.CreditService;
import dev.branzx.wallet.service.LinkService;
import dev.branzx.wallet.storage.WalletDatabase;
import net.milkbowl.vault.economy.Economy;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.plugin.ServicePriority;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.UUID;

/**
 * Central currency plugin. Owns Coin (the MMO economy, published via Vault) and
 * Credit (premium, real-money). Other plugins and the Discord bot go through the
 * registered {@link WalletApi} service; nothing else should touch wallet tables.
 */
public final class WalletPlugin extends JavaPlugin {

    private WalletDatabase database;
    private CoinService coinService;
    private CreditService creditService;
    private LinkService linkService;

    @Override
    public void onEnable() {
        saveDefaultConfig();

        database = new WalletDatabase(this);
        database.init();

        coinService = new CoinService(this, database);
        // Season identity is a fixed "preseason" for now; a live-ops season
        // provider can replace this supplier later without touching the ledger.
        creditService = new CreditService(this, database, coinService,
                () -> getConfig().getString("season.id", "preseason"));
        linkService = new LinkService(this, database);

        WalletApi api = new WalletApiImpl(coinService, creditService, linkService);
        getServer().getServicesManager().register(WalletApi.class, api, this, ServicePriority.Normal);

        registerVault();

        getLogger().info("BranzWallet enabled — Coin + Credit are now central.");
    }

    @Override
    public void onDisable() {
        if (database != null) {
            database.shutdown();
        }
    }

    /** Registers the Coin economy with Vault when Vault is present. */
    private void registerVault() {
        if (getServer().getPluginManager().getPlugin("Vault") == null) {
            getLogger().warning("Vault not found — Coin will not be exposed as a Vault economy.");
            return;
        }
        WalletEconomy economy = new WalletEconomy(this, coinService);
        getServer().getServicesManager().register(Economy.class, economy, this, ServicePriority.High);
        getLogger().info("Registered Coin as the Vault economy provider.");
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage("Only players can use /wallet.");
            return true;
        }
        UUID uuid = player.getUniqueId();
        coinService.ensureAccount(uuid, player.getName());

        if (args.length == 0 || args[0].equalsIgnoreCase("balance")) {
            player.sendMessage("§eCoins: §f" + coinService.balance(uuid)
                    + "  §bCredits: §f" + creditService.balance(uuid));
            return true;
        }
        if (args[0].equalsIgnoreCase("link")) {
            String code = linkService.mintCode(uuid);
            long ttl = getConfig().getLong("link.code-ttl-minutes", 5);
            player.sendMessage("§aLink code: §f§l" + code
                    + " §7— type §f/link " + code + " §7in Discord within " + ttl + " min.");
            return true;
        }
        player.sendMessage("§7Usage: /wallet [balance|link]");
        return true;
    }
}
