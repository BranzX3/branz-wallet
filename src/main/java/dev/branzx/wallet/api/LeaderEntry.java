package dev.branzx.wallet.api;

import java.util.UUID;

/** One row of a Coin leaderboard: a player and their Coin balance. */
public record LeaderEntry(UUID uuid, String name, long coins) {
}
