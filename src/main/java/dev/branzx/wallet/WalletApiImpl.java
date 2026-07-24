package dev.branzx.wallet;

import dev.branzx.wallet.api.Checkout;
import dev.branzx.wallet.api.LedgerEntry;
import dev.branzx.wallet.api.TopupInfo;
import dev.branzx.wallet.api.TopupSettlement;
import dev.branzx.wallet.api.WalletApi;
import dev.branzx.wallet.service.CoinService;
import dev.branzx.wallet.service.CreditService;
import dev.branzx.wallet.service.LinkService;
import dev.branzx.wallet.service.TopupService;
import dev.branzx.wallet.service.WarehouseService;

import java.util.List;
import java.util.UUID;

/** Thin adapter that exposes the internal services as the public {@link WalletApi}. */
final class WalletApiImpl implements WalletApi {

    private final CoinService coins;
    private final CreditService credits;
    private final LinkService links;
    private final TopupService topups;
    private final WarehouseService warehouse;

    WalletApiImpl(CoinService coins, CreditService credits, LinkService links,
                  TopupService topups, WarehouseService warehouse) {
        this.coins = coins;
        this.credits = credits;
        this.links = links;
        this.topups = topups;
        this.warehouse = warehouse;
    }

    @Override
    public java.util.Map<String, Integer> warehouseContents(UUID owner) {
        return warehouse.contents(owner);
    }

    @Override
    public int warehouseAmount(UUID owner, String itemKey) {
        return warehouse.amount(owner, itemKey);
    }

    @Override
    public boolean warehouseDeposit(UUID owner, String itemKey, int amount) {
        return warehouse.deposit(owner, itemKey, amount);
    }

    @Override
    public boolean warehouseWithdraw(UUID owner, String itemKey, int amount) {
        return warehouse.withdraw(owner, itemKey, amount);
    }

    @Override
    public boolean createTopup(String reference, UUID owner, long credits,
                               long amountSatang, String packageId) {
        return topups.create(reference, owner, credits, amountSatang, packageId);
    }

    @Override
    public TopupSettlement settleTopup(String reference, String providerRef) {
        return topups.settle(reference, providerRef);
    }

    @Override
    public TopupInfo topup(String reference) {
        return topups.get(reference);
    }

    @Override
    public long coins(UUID owner) {
        return coins.balance(owner);
    }

    @Override
    public boolean addCoins(UUID owner, long amount) {
        return coins.add(owner, amount);
    }

    @Override
    public java.util.List<dev.branzx.wallet.api.LeaderEntry> topCoins(int limit) {
        return coins.top(limit);
    }

    @Override
    public long credits(UUID owner) {
        return credits.balance(owner);
    }

    @Override
    public boolean adjustCredit(UUID owner, long amount, String type,
                                String transactionId, String detail) {
        return credits.adjust(owner, amount, type, transactionId, detail);
    }

    @Override
    public void recordCoinsEarned(UUID owner, long amount) {
        credits.recordCoinsEarned(owner, amount);
    }

    @Override
    public List<LedgerEntry> creditHistory(UUID owner, int limit) {
        return credits.historySync(owner, limit);
    }

    @Override
    public void invalidateCredit(UUID owner) {
        credits.invalidate(owner);
    }

    @Override
    public Checkout hybridPay(UUID owner, long coinPrice, long requestedCredits,
                              String purchaseId, String idempotencyKey) {
        return credits.hybridPay(owner, coinPrice, requestedCredits, purchaseId, idempotencyKey);
    }

    @Override
    public UUID redeemLinkCode(String code, String discordId) {
        return links.redeem(code, discordId);
    }

    @Override
    public UUID linkedUuid(String discordId) {
        return links.linkedUuid(discordId);
    }

    @Override
    public String discordIdFor(UUID owner) {
        return links.discordIdFor(owner);
    }
}
