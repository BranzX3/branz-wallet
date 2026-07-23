package dev.branzx.wallet.api;

import java.util.List;
import java.util.UUID;

/**
 * The stable, cross-plugin surface of BranzWallet. Other plugins (Idle,
 * dungeons, ...) and the Discord bot bridge all currency through this
 * interface rather than touching wallet tables directly.
 *
 * <p>Registered as a Bukkit service; obtain it with:
 * <pre>{@code
 * WalletApi wallet = getServer().getServicesManager().load(WalletApi.class);
 * }</pre>
 *
 * <p>Two currencies live here:
 * <ul>
 *   <li><b>Coin</b> — the MMO economy, also published as the Vault {@code Economy}.
 *       Earned from gameplay across the whole network.</li>
 *   <li><b>Credit</b> — premium, integer, non-transferable, bought with real
 *       money. Granted idempotently by {@link #adjustCredit}.</li>
 * </ul>
 *
 * <p>Every mutation is a relative, floor-guarded UPDATE against the shared
 * database, so a grant landing on another backend (or from the payment webhook)
 * is never clobbered by a stale cached snapshot on this one.
 */
public interface WalletApi {

    // ---- Coin (MMO currency) ----

    /** Current Coin balance of {@code owner}. */
    long coins(UUID owner);

    /**
     * Adds {@code amount} Coins (may be negative to debit). Returns false if a
     * debit would overdraw. Atomic and safe to race with other backends.
     */
    boolean addCoins(UUID owner, long amount);

    // ---- Credit (premium currency) ----

    /** Current Credit balance of {@code owner}. */
    long credits(UUID owner);

    /**
     * Idempotent Credit grant/deduction — the entry point the Discord top-up
     * bot and admin tools use. Replaying the same {@code transactionId} cannot
     * mint Credits twice: it is the ledger's primary key, so a duplicate aborts
     * the whole transaction and nothing is granted. A deduction can never
     * overdraw. Returns true only when a new ledger row committed.
     */
    boolean adjustCredit(UUID owner, long amount, String type,
                         String transactionId, String detail);

    /**
     * Records Coins a player earned this season, feeding the Hybrid Pay offset
     * cap. Fire-and-forget; safe off the main thread.
     */
    void recordCoinsEarned(UUID owner, long amount);

    /** Most recent Credit ledger entries for {@code owner}, newest first. */
    List<LedgerEntry> creditHistory(UUID owner, int limit);

    /** Drops any cached Credit snapshot for {@code owner} (call on join/quit). */
    void invalidateCredit(UUID owner);

    /**
     * Pays a fixed-price Coin checkout, optionally offsetting part of it with
     * Credit. Idempotent on {@code idempotencyKey}. See implementation for the
     * offset bounds.
     */
    Checkout hybridPay(UUID owner, long coinPrice, long requestedCredits,
                       String purchaseId, String idempotencyKey);

    // ---- account linking (Discord <-> Minecraft) ----

    /**
     * Consumes a 6-digit link code the player generated in-game and binds it to
     * {@code discordId}. Returns the linked Minecraft UUID, or null if the code
     * is unknown/expired/used.
     */
    UUID redeemLinkCode(String code, String discordId);

    /** Minecraft UUID linked to {@code discordId}, or null if none. */
    UUID linkedUuid(String discordId);
}
