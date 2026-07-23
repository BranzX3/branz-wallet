package dev.branzx.wallet.api;

/** Result of settling a top-up order. */
public enum TopupSettlement {
    /** Credit was granted for the first time. */
    GRANTED,
    /** The order was already PAID; nothing changed (a replayed webhook). */
    ALREADY_SETTLED,
    /** No such reference, or the settlement could not be committed. */
    UNKNOWN,
}
