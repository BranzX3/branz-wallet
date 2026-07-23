package dev.branzx.wallet.api;

/**
 * One immutable row of the Credit ledger, as returned by
 * {@link WalletApi#creditHistory}. {@code amount} is signed: positive for a
 * grant, negative for a spend.
 */
public record LedgerEntry(String transactionId, String type, long amount,
                          String detail, String createdAt) {
}
