package dev.branzx.wallet;

import dev.branzx.wallet.api.Checkout;
import dev.branzx.wallet.api.WalletApi;
import dev.branzx.wallet.service.CoinService;
import dev.branzx.wallet.service.CreditService;
import dev.branzx.wallet.service.LinkService;

import java.util.UUID;

/** Thin adapter that exposes the internal services as the public {@link WalletApi}. */
final class WalletApiImpl implements WalletApi {

    private final CoinService coins;
    private final CreditService credits;
    private final LinkService links;

    WalletApiImpl(CoinService coins, CreditService credits, LinkService links) {
        this.coins = coins;
        this.credits = credits;
        this.links = links;
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
    public long credits(UUID owner) {
        return credits.balance(owner);
    }

    @Override
    public boolean adjustCredit(UUID owner, long amount, String type,
                                String transactionId, String detail) {
        return credits.adjust(owner, amount, type, transactionId, detail);
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
}
