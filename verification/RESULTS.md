# H2 benchmark and requirement verification

Run: 2026-09-12T07:52:25.254807Z to 2026-09-12T07:53:52.051752Z (UTC).

The unchanged H2 trading implementation was tested through its real HTTP endpoint. The correctness build passed **37 tests, 0 failures, 0 errors, 0 skipped**. The benchmark is a separate explicit test and its assertion result is in [benchmark-run.log](benchmark-run.log).

## Measured results

| Metric | 100 independent users | 100 clients sharing one wallet |
| --- | --- | --- |
| Users / concurrent clients | 100 / 100 | 1 / 100 |
| Measured seconds, including final responses | 60.01 | 15.04 |
| Requests / successful responses | 221,513 / 221,513 | 36,560 / 36,560 |
| Success rate | 100.0000% | 100.0000% |
| Successful trades/second | 3,691.57 | 2,430.47 |
| Mean / p50 latency (ms) | 27.05 / 24.86 | 41.02 / 39.77 |
| p95 / p99 latency (ms) | 47.05 / 73.16 | 48.95 / 75.76 |
| Maximum latency (ms) | 329.57 | 253.33 |
| Requests strictly below 200 ms | 221,489 (99.9892%) | 36,559 (99.9973%) |
| Requests at least 200 ms | 24 | 1 |
| Peak client requests in flight, including warmup | 100 | 100 |
| Balance mismatches / wallets reconciled | 0 / 300 | 0 / 3 |
| Negative wallets / duplicate keys | 0 / 0 | 0 / 0 |
| Missing acknowledged trades / incomplete audit rows | 0 / 0 | 0 / 0 |
| Committed trades without acknowledgement | 0 | 0 |
| Maximum accepted fixture quote age (ms) | 252.39 | 325.44 |

HTTP status counts: independent users `{"200": 221513}`; shared wallet `{"200": 36560}`. Error codes: independent users `{}`; shared wallet `{}`. Fixture publication failures: **0**.

## Requirement comparison

The requirements come from [1_BUSINESS_REQUIREMENTS.md](../1_BUSINESS_REQUIREMENTS.md). A short benchmark only supports the conclusions listed below.

| Requirement | Result | Evidence / scope |
| --- | --- | --- |
| Trading API response times under 200 ms | **NOT MET in this run** | The requirement specifies no percentile. Treating it literally means every measured response must be below 200 ms. The table reports all outliers. p95 is a diagnostic, not a substitute requirement. |
| Minimum 100 simultaneous trades | **PASS for this run** | 100 barrier-started clients per scenario; peak client requests in flight reached 100. This includes HTTP/database queueing and does not assert 100 database transactions execute at once. Correctness tests separately exercise 100-thread contention. |
| API success rate >99.5% | **PASS for this run** | Measured first-attempt responses for funded, valid requests; all HTTP/transport errors stay in the denominator. No retries hide errors. |
| Minimum 1,000 trades/day | Local capacity demonstrated; daily operation unmeasured | 258,073 successful measured trades were executed in these short runs. This exceeds 1,000 actual executions, but is not a 24-hour availability/throughput test or a claim of daily customer volume. |
| Typical $100–$10,000 trade size | Fixture workload within range under stated assumption | BTCUSDT quantity 0.01 settles 500–500.10 USDT; ETHUSDT quantity 0.2 settles 600–600.20 USDT. No live USD conversion was measured. |
| Zero balance calculation errors | No errors observed | All 303 benchmark wallets reconciled exactly against opening balances plus committed trade deltas; negative wallets and mismatches are reported above. Unit tests cover rounding, dust, and overflow boundaries. |
| Complete transaction audit trail | Verified for this run | 276,437 persisted trades including warmup were checked against acknowledgement IDs. Missing acknowledgements, incomplete metadata, and duplicate keys are reported explicitly. |
| Immutable completed transactions | Application design evidence only | The trade mapper/API exposes insert/read operations. This benchmark does not prove immutability against direct H2 SQL or administrator access. |
| Precise execution timestamps | Exercised | Every benchmark trade has execution and price-observation timestamps; accepted quote ages were measured from persisted timestamps. No external clock synchronization accuracy was measured. |
| Price feeds with <1 second latency | Freshness enforcement exercised; live feed latency unmeasured | Live Clock timestamps on controlled fixtures are refreshed every 100 ms; normal freshness validation remains enabled. External exchange latency and underlying order-book event age are not measured. |
| Automated recovery from external API failures | Partial functional evidence | The correctness suite tests one-source failure/backoff and healthy-source fallback. This run does not test real provider outages or full outage-to-recovery behavior. |
| 99.9% uptime during trading hours | Not measured | Neither a 60-second load test nor a 15-second contention test establishes a long-term uptime SLO. |
| Zero data loss incidents | No acknowledged-trade loss observed during these runs; restart durability excluded | Audit reconciliation covers the running process. In-memory H2 is intentionally retained and loses its database on process exit; no restart-durability claim is made. |
| Unauthorized-access protection | Outside this measurement | Requests follow the assessment's pre-authenticated assumption and supplied security configuration. No JWT infrastructure was added. |

```java
// ASSUMPTION: For the fixture trade-size comparison only, one USDT is treated as roughly one USD.
// This benchmark does not obtain exchange rates or claim that a stablecoin always trades at parity.
// REASON: Report both literal all-request latency and percentile diagnostics. Never discard slow
// successful requests, business errors, or transport failures to improve the reported result.
```

## Method and environment

- Real embedded Tomcat, HTTP/1.1, and in-memory H2; no service or mapper mocks in the benchmark.
- Five-second warmup per scenario, excluded from latency/success/throughput metrics; included in audit reconciliation.
- 100 closed-loop clients: each sends its next request after receiving the previous response. Each scenario starts with a barrier. Request duration runs from immediately before HTTP send until the full response body is received. Client-side JSON decoding is excluded.
- Independent-user scenario: 100 separately funded users, 60 measured seconds. Contention scenario: all clients share one user, 15 measured seconds.
- Both scenarios alternate BUY/SELL and BTCUSDT/ETHUSDT with unique request keys. Each user begins with 10,000,000 USDT, 100 BTC, and 1,000 ETH to isolate concurrency from intentional insufficient-funds rejection.
- Real-time fixture quote updates every 100 ms replace external fetching, while keeping the one-second trade freshness check. The HTTP timeout is five seconds. There are no automatic request retries.
- Environment: Mac OS X 15.7.9, aarch64, Java 21.0.7, H2 2.3.232 (2024-08-11); 8 JVM-visible processors, 4.00 GiB maximum JVM heap, Hikari maximum pool size 10.
- Client and application share the machine/JVM. JaCoCo and Mockito Java agents are enabled by the existing test configuration. No production application code or connection-pool tuning was changed for this benchmark.

```java
// LIMITATION: This is a local closed-loop assessment benchmark. It does not model independent
// fixed-rate arrivals, remote-network latency, TLS, multiple application instances, or a long soak.
// The p95/p99 values use nearest-rank percentiles over every measured attempt, including failures.
```

## Correctness and coverage

The **37 passing tests** cover BUY/SELL pricing, exact-balance spending, lazy wallet creation, insufficient funds, decimal limits/rounding, rollback after debit or credit, matching/conflicting idempotency keys, 100 concurrent same-user requests, mixed BUY/SELL, shared USDT across pairs, 100 independent users, stale/future/crossed quotes, provider parsing/fallback, scheduler activation, and HTTP validation.

Functional-suite coverage before the benchmark: **90.03% lines** (352/391) and **71.58% branches** (136/190). Coverage is supporting information, not proof of correctness.

## Files and reproduction

- [benchmark-results.json](benchmark-results.json): machine-readable metrics and conditions.
- [test-results.json](test-results.json): suite totals and individual correctness-test names/results.
- [coverage-results.json](coverage-results.json): functional-suite coverage counters.
- [test-run.log](test-run.log) and [benchmark-run.log](benchmark-run.log): Maven execution evidence.
- [independent-users-samples.csv.gz](independent-users-samples.csv.gz) and [shared-wallet-samples.csv.gz](shared-wallet-samples.csv.gz): raw latency/status/trade-ID samples, including a warmup/measurement flag.
- [source-sha256.txt](source-sha256.txt): fingerprints of the measured application source/configuration/migrations and POM.
- Benchmark harness: [H2Benchmark.java](../src/test/java/com/aquariux/technical/assessment/trade/H2Benchmark.java).

From the project root with Java 21:

```bash
./mvnw --batch-mode --no-transfer-progress clean verify > verification/test-run.log 2>&1
python3 verification/collect_tests.py
./mvnw --batch-mode --no-transfer-progress -Dtest=H2Benchmark test > verification/benchmark-run.log 2>&1
python3 verification/summarize.py
```

Override durations with `-Dbenchmark.seconds=60 -Dbenchmark.contention.seconds=15` (defaults shown). The explicit benchmark class name keeps it out of normal correctness-test runs. Maven may require network access on the first dependency download; the benchmark itself only uses loopback HTTP and H2. No Docker or database replacement is required.

Run the commands in that order: `collect_tests.py` records functional coverage before the benchmark adds its own execution data. The benchmark regenerates its JSON and raw samples; `summarize.py` builds this report from those snapshots.
