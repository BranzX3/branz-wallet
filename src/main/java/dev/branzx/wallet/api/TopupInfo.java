package dev.branzx.wallet.api;

import java.util.UUID;

/**
 * A top-up order. {@code amountSatang} is the real-money amount in satang (1/100
 * baht) to avoid floating point; {@code credits} is what the buyer receives on
 * settlement. {@code status} is PENDING, PAID, or EXPIRED.
 */
public record TopupInfo(String reference, UUID owner, long credits, long amountSatang,
                        String packageId, String status) {
}
