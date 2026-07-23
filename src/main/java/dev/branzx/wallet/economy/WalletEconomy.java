package dev.branzx.wallet.economy;

import dev.branzx.wallet.service.CoinService;
import net.milkbowl.vault.economy.Economy;
import net.milkbowl.vault.economy.EconomyResponse;
import org.bukkit.OfflinePlayer;
import org.bukkit.plugin.Plugin;

import java.util.List;
import java.util.UUID;

/**
 * Publishes Coin to the whole network as the Vault economy. Every operation
 * routes through {@link CoinService}, whose mutations are relative and
 * floor-guarded against the shared database — nothing here reads a balance and
 * writes it back. Banks are unsupported: there is no account model behind them.
 */
public final class WalletEconomy implements Economy {

    private final Plugin plugin;
    private final CoinService coins;

    public WalletEconomy(Plugin plugin, CoinService coins) {
        this.plugin = plugin;
        this.coins = coins;
    }

    @Override
    public boolean isEnabled() {
        return plugin.isEnabled();
    }

    @Override
    public String getName() {
        return plugin.getName();
    }

    @Override
    public boolean hasBankSupport() {
        return false;
    }

    @Override
    public int fractionalDigits() {
        return 0;
    }

    @Override
    public String format(double amount) {
        return Math.round(amount) + " " + currencyNamePlural();
    }

    @Override
    public String currencyNamePlural() {
        return plugin.getConfig().getString("currency-name", "Coins");
    }

    @Override
    public String currencyNameSingular() {
        return currencyNamePlural();
    }

    private UUID id(OfflinePlayer player) {
        return player == null ? null : player.getUniqueId();
    }

    @SuppressWarnings("deprecation")
    private OfflinePlayer byName(String playerName) {
        return plugin.getServer().getOfflinePlayer(playerName);
    }

    @Override
    public boolean hasAccount(OfflinePlayer player) {
        return id(player) != null;
    }

    @Override
    public boolean hasAccount(OfflinePlayer player, String worldName) {
        return hasAccount(player);
    }

    @Override
    public boolean createPlayerAccount(OfflinePlayer player) {
        UUID uuid = id(player);
        if (uuid == null) return false;
        coins.ensureAccount(uuid, player.getName());
        return true;
    }

    @Override
    public boolean createPlayerAccount(OfflinePlayer player, String worldName) {
        return createPlayerAccount(player);
    }

    @Override
    public double getBalance(OfflinePlayer player) {
        UUID uuid = id(player);
        return uuid == null ? 0 : coins.balance(uuid);
    }

    @Override
    public double getBalance(OfflinePlayer player, String world) {
        return getBalance(player);
    }

    @Override
    public boolean has(OfflinePlayer player, double amount) {
        return getBalance(player) >= amount;
    }

    @Override
    public boolean has(OfflinePlayer player, String worldName, double amount) {
        return has(player, amount);
    }

    @Override
    public EconomyResponse withdrawPlayer(OfflinePlayer player, double amount) {
        UUID uuid = id(player);
        if (uuid == null) {
            return failure(0, "Unknown player");
        }
        if (amount < 0) {
            return failure(coins.balance(uuid), "Cannot withdraw a negative amount");
        }
        if (!coins.add(uuid, -Math.round(amount))) {
            return failure(coins.balance(uuid), "Insufficient " + currencyNamePlural());
        }
        return new EconomyResponse(amount, coins.balance(uuid),
                EconomyResponse.ResponseType.SUCCESS, null);
    }

    @Override
    public EconomyResponse withdrawPlayer(OfflinePlayer player, String worldName, double amount) {
        return withdrawPlayer(player, amount);
    }

    @Override
    public EconomyResponse depositPlayer(OfflinePlayer player, double amount) {
        UUID uuid = id(player);
        if (uuid == null) {
            return failure(0, "Unknown player");
        }
        if (amount < 0) {
            return failure(coins.balance(uuid), "Cannot deposit a negative amount");
        }
        if (!coins.add(uuid, Math.round(amount))) {
            return failure(coins.balance(uuid), "No account for this player");
        }
        return new EconomyResponse(amount, coins.balance(uuid),
                EconomyResponse.ResponseType.SUCCESS, null);
    }

    @Override
    public EconomyResponse depositPlayer(OfflinePlayer player, String worldName, double amount) {
        return depositPlayer(player, amount);
    }

    private EconomyResponse failure(double balance, String message) {
        return new EconomyResponse(0, balance, EconomyResponse.ResponseType.FAILURE, message);
    }

    private EconomyResponse noBanks() {
        return new EconomyResponse(0, 0, EconomyResponse.ResponseType.NOT_IMPLEMENTED,
                "BranzWallet does not support bank accounts");
    }

    // ---- deprecated name-keyed overloads ----

    @Override
    public boolean hasAccount(String playerName) {
        return hasAccount(byName(playerName));
    }

    @Override
    public boolean hasAccount(String playerName, String worldName) {
        return hasAccount(byName(playerName));
    }

    @Override
    public double getBalance(String playerName) {
        return getBalance(byName(playerName));
    }

    @Override
    public double getBalance(String playerName, String world) {
        return getBalance(byName(playerName));
    }

    @Override
    public boolean has(String playerName, double amount) {
        return has(byName(playerName), amount);
    }

    @Override
    public boolean has(String playerName, String worldName, double amount) {
        return has(byName(playerName), amount);
    }

    @Override
    public EconomyResponse withdrawPlayer(String playerName, double amount) {
        return withdrawPlayer(byName(playerName), amount);
    }

    @Override
    public EconomyResponse withdrawPlayer(String playerName, String worldName, double amount) {
        return withdrawPlayer(byName(playerName), amount);
    }

    @Override
    public EconomyResponse depositPlayer(String playerName, double amount) {
        return depositPlayer(byName(playerName), amount);
    }

    @Override
    public EconomyResponse depositPlayer(String playerName, String worldName, double amount) {
        return depositPlayer(byName(playerName), amount);
    }

    @Override
    public boolean createPlayerAccount(String playerName) {
        return createPlayerAccount(byName(playerName));
    }

    @Override
    public boolean createPlayerAccount(String playerName, String worldName) {
        return createPlayerAccount(byName(playerName));
    }

    // ---- banks (unsupported) ----

    @Override
    public EconomyResponse createBank(String name, OfflinePlayer player) {
        return noBanks();
    }

    @Override
    public EconomyResponse createBank(String name, String player) {
        return noBanks();
    }

    @Override
    public EconomyResponse deleteBank(String name) {
        return noBanks();
    }

    @Override
    public EconomyResponse bankBalance(String name) {
        return noBanks();
    }

    @Override
    public EconomyResponse bankHas(String name, double amount) {
        return noBanks();
    }

    @Override
    public EconomyResponse bankWithdraw(String name, double amount) {
        return noBanks();
    }

    @Override
    public EconomyResponse bankDeposit(String name, double amount) {
        return noBanks();
    }

    @Override
    public EconomyResponse isBankOwner(String name, OfflinePlayer player) {
        return noBanks();
    }

    @Override
    public EconomyResponse isBankOwner(String name, String playerName) {
        return noBanks();
    }

    @Override
    public EconomyResponse isBankMember(String name, OfflinePlayer player) {
        return noBanks();
    }

    @Override
    public EconomyResponse isBankMember(String name, String playerName) {
        return noBanks();
    }

    @Override
    public List<String> getBanks() {
        return List.of();
    }
}
