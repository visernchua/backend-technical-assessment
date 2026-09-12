# Assessment implementation plan

Implement `POST /api/trades/execute` using the supplied **H2, Spring Boot, MyBatis, and Flyway** stack. The focus is trading correctness under concurrent requests and failure conditions. The original requirement, schema, overview, assessment, and README documents remain unchanged.

## Assumptions and reasons

```java
// A01/A04 — ASSUMPTION: Execute full BUY/SELL trades internally against aggregated market prices.
// The assessment does not require submitting exchange orders, partial fills, or fees.
// A02 — ASSUMPTION: quantity always denotes the pair's base asset (BTC or ETH).
// A05 — DECISION: Use BigDecimal and DECIMAL(20,8), rounding the quote amount once with HALF_EVEN.
// REASON: Wallet updates, persisted trades, and responses must use the same exact settlement amount.
// A06 — ASSUMPTION: Requests are already authenticated, as stated in the business requirements.
// Keep the supplied security setup; implementing identity infrastructure is outside this assessment.
// A07 — DECISION: Lock the user's database row before reading/updating wallets.
// REASON: A user row exists even before the receiving wallet does. This closes both the
// overspending race and the concurrent first-wallet-creation race without a global trading lock.
// A08 — DECISION: Require a per-user Idempotency-Key and retain it on a successful trade.
// REASON: Retrying after an uncertain HTTP response must not execute a second trade.
// A09 — ASSUMPTION: A selected quote must be less than one second old after acquiring locks.
// Provider-response/receipt times are freshness proxies, not proven market-event timestamps.
// A10 — DECISION: A healthy source may supply the quote if the other source fails.
// A11 — ASSUMPTION: Reject crossed aggregate books for this internal settlement model.
// REASON: Unrestricted internal fills at a crossed book could create repeatable arbitrage.
// A12 — DECISION: Create an absent destination wallet only inside a successful credit transaction.
// A15 — DECISION: The existing trades table records successful executions; failed attempts do not
// create trade rows. The application exposes no trade update/delete operation.
```

## Execution flow

1. Validate user ID, supported pair, side, positive base quantity, decimal limits, and request key.
2. Begin one transaction and lock the user row with `SELECT ... FOR UPDATE`.
3. Return the stored trade for the same key and canonical payload; reject conflicting reuse.
4. Validate the pair and symbols. Read and lock the user's wallets in a consistent order.
5. Select a deterministic latest quote and verify source, freshness, and price validity.
6. BUY uses ask and debits USDT; SELL uses bid and debits the base asset. Calculate one settlement amount, then check funds and credit overflow.
7. Perform guarded debit and credit (or destination insertion), checking affected-row counts.
8. Insert the trade and commit. Any failure rolls back both balances, wallet creation, and trade insertion.

```java
// ASSUMPTION: Pair/symbol administration is outside the assessment; active flags are checked at
// execution. Trading does not lock an entire pair, so independent users can trade concurrently.
// REASON: All future wallet writers must follow the same per-user locking convention.
// Initial funding is outside the API. No reservation ledger is needed for synchronous full fills.
```

## Supporting changes

- Correct the scheduler's swapped pair IDs and swapped bid/ask/source persistence.
- Enable non-overlapping scheduled ingestion, bound provider waits, isolate source failures, and reject invalid/stale quotes.
- Add a small H2 migration for numeric constraints, request-key uniqueness, quote timestamps, and trade price provenance. Keep the original six tables and existing V1–V4 migrations.
- Use structured validation/business errors, UTC execution times, and exact decimal response values.
- Restore the Maven wrapper and add the validation dependency.

## Verification

Use real H2 transactions to test BUY/SELL settlement, exact-balance spending, insufficient funds, wallet creation, rounding/overflow, invalid/stale prices, duplicate requests, and rollback after debit or credit. Launch 100 simultaneous requests for overspending, identical-key replay, mixed BUY/SELL, and independent-user cases. Test price aggregation regressions and HTTP validation separately.