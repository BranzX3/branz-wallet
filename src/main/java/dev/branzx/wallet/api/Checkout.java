package dev.branzx.wallet.api;

/**
 * Outcome of a {@link WalletApi#hybridPay} call: whether it committed, a
 * human-readable message, and how much of each currency was actually charged.
 */
public record Checkout(boolean success, String message, long coinsCharged, long creditsCharged) {

    public static Checkout fail(String message) {
        return new Checkout(false, message, 0, 0);
    }
}
