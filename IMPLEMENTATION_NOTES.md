# Trading assessment implementation

The implementation keeps the supplied **in-memory H2 database** and existing controller/service/mapper structure. Changes focus on robust trade execution and the pricing defects that directly affect it. The original assessment Markdown files and V1–V4 migrations are unchanged.

## Trading behavior

```java
// ASSUMPTION: The request is pre-authenticated, as specified in the requirements.
// ASSUMPTION: quantity is base-asset quantity; BUY spends USDT and SELL spends BTC or ETH.
// REASON: BUY executes at the lowest ask; SELL executes at the highest bid.
// REASON: BigDecimal arithmetic and one HALF_EVEN rounding step prevent inconsistent settlement.
// ASSUMPTION: Full immediate fills, no fees, no exchange order submission, no cancellation.
```

A database transaction locks the user row before reading balances. This protects both existing wallets and first-time wallet creation. Different users do not share a trading lock. Wallet updates additionally use guarded SQL, preventing negative debit balances and credit overflow. Both wallet updates and the trade record commit together; an exception rolls everything back.

A required `Idempotency-Key` is unique per user. An identical successful request returns the original trade, even if the market price or wallet balance has since changed. A different payload under that key returns 409. A failed trade leaves no key reservation, so a subsequent retry is evaluated against the current state.

```java
// REASON: A client can lose the response after the database has committed. Reuse the SAME key
// when retrying an uncertain request; generating a new key would represent another trade.
// ASSUMPTION: All wallet writers, including any future funding integration, use the same user lock.
// REASON: No wallet-movement ledger is necessary for this assessment; completed trades provide
// the required audit history, and the API contains no trade update/delete operations.
```

The receiving wallet is inserted only when credited. Wallet reads report zero for supported currencies not yet acquired, without creating database rows. Numeric constraints and request-key uniqueness are added by `V5__Trade_execution_safeguards.sql`; no new tables are introduced.

## Pricing

The scheduler now resolves the actual BTC/ETH pair and persists bid/ask values and sources correctly. Provider calls have bounded waits and independent failure handling. Latest-price selection breaks timestamp ties deterministically. The trade service checks freshness after acquiring locks so a request cannot spend time waiting and then execute at an expired quote.

```java
// ASSUMPTION: Use one application instance with its supplied in-memory H2 database.
// REASON: fixedDelay scheduling avoids overlapping runs; distributed leases are unnecessary.
// ASSUMPTION: Quote age must be below one second. Old sample prices cannot execute trades.
// REASON: Binance receipt time and Huobi response-generation time are freshness proxies;
// neither supplied endpoint proves the timestamp of the underlying order-book event.
// ASSUMPTION: Reject a crossed aggregate to avoid repeated arbitrage in internal full-fill settlement.
```

The polling delay is configurable. A source outage can use the remaining valid source; if no valid fresh price exists, execution returns 503 without changing balances. The price API exposes observation times and whether the latest snapshot is currently executable.

## Run and verify

Use Java 21. No Docker, PostgreSQL, or external authentication configuration is required.

```bash
./mvnw clean verify
./mvnw spring-boot:run
```

H2 contains the original sample users and balances. Wait for the scheduler to obtain a fresh valid quote before executing; historical sample prices are intentionally rejected. Swagger remains available at `/swagger-ui/index.html`.

```bash
curl -i http://localhost:8080/api/trades/execute \
  -H 'Content-Type: application/json' \
  -H 'Idempotency-Key: buy-btc-001' \
  -d '{"userId":1,"pairName":"BTCUSDT","tradeType":"BUY","quantity":"0.01000000"}'
```

The response contains immutable trade details with exact decimal strings and UTC execution time. Validation errors return 400; missing users/pairs return 404; insufficient funds, inactive pairs, key conflicts, or balance overflow return 409; unrepresentable settlement amounts return 422; missing/stale/invalid prices and transient database contention return 503.

The tests use real H2 transactions and cover:

- Correct BUY/SELL prices, exact-balance spending, lazy wallet creation, rounding, and numeric limits.
- Rollback after debit or credit when a later database operation fails.
- 100 concurrent requests competing for one balance, 100 identical-key retries, concurrent BUY/SELL orders, shared USDT across pairs, and 100 independent users.
- Invalid/stale/future/crossed prices, deterministic latest selection, source failures, scheduler activation, and HTTP validation.

Assumptions and design reasons are commented alongside their implementation and summarized in [the focused plan](TRADE_EXECUTION_IMPLEMENTATION_PLAN.md).
