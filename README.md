# BranzWallet

Central currency plugin for the Branz Minecraft network. Owns **two** currencies
so every backend (survival, dungeons, …) and the Discord storefront share one
authoritative source:

- **Coin** — the MMO in-game economy. Published to the whole server as the Vault
  `Economy`, so any Vault-aware plugin reads and charges it.
- **Credit** — premium currency bought with real money. Integer,
  non-transferable, granted idempotently through an immutable ledger.

## Why a separate plugin

Idle used to own both currencies. As the network grew past Idle, the money had to
become shared infrastructure. Coin and Credit now live here; Idle (and future
plugins) depend on `WalletApi` instead of holding their own wallet tables.

Coin debits stay **atomic with gameplay**: `CoinService.addWithin(connection, …)`
runs a floor-guarded relative UPDATE on a caller-supplied connection, so Idle can
charge for a claim in the very same transaction that writes the node row. That is
the invariant that justified keeping the currency in-process before, preserved
across the move by sharing one database.

## Public API

Obtain via Bukkit services:

```java
WalletApi wallet = getServer().getServicesManager().load(WalletApi.class);
long coins = wallet.coins(uuid);
wallet.adjustCredit(uuid, 100, "TOPUP", "promptpay:tx123", "{...}"); // idempotent
```

## Account linking (Discord ↔ Minecraft)

1. In-game: `/wallet link` → mints a 6-digit code (5-min TTL).
2. In Discord: `/link <code>` → the bot calls `WalletApi.redeemLinkCode`.
3. `WalletApi.linkedUuid(discordId)` resolves the binding thereafter.

## Storage

One shared database (`storage.type: mysql` for the network; `sqlite` for local
dev). Tables: `wallet_accounts` (Coin), `wallet_credit` + `wallet_credit_ledger`
(Credit), `wallet_link_code`, `wallet_discord_link`. Schema is created on enable.

## Build

```bash
./gradlew build
```

Output: `build/libs/BranzWallet-1.0.0.jar` → drop into `survival/plugins/`
(Vault recommended, LuckPerms needed later for rank sales).

## Roadmap

- [ ] Refactor Idle to depend on `WalletApi` and drop its own `CreditService` /
      `IdleEconomy` (migrate `idle_credit_*` → `wallet_*`, `idle_players.balance`
      → `wallet_accounts.coins`).
- [ ] Rank sales via LuckPerms group grant + Discord role.
- [ ] Discord bot: `/link`, `/balance`, `/topup`, `/buyrank`.
- [ ] PromptPay/TrueMoney gateway → webhook → `adjustCredit`.
