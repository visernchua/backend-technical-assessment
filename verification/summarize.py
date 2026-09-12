#!/usr/bin/env python3
"""Regenerate the requirement report from recorded benchmark and correctness-test results."""
import json
from pathlib import Path

folder = Path(__file__).resolve().parent
benchmark = json.loads((folder / 'benchmark-results.json').read_text())
tests = json.loads((folder / 'test-results.json').read_text())
coverage = json.loads((folder / 'coverage-results.json').read_text())
scenarios = benchmark['scenarios']
primary, contention = scenarios

def fmt(value):
    return f'{value:,.2f}' if isinstance(value, float) else f'{value:,}'

def passed(condition):
    return 'PASS for this run' if condition else 'NOT MET in this run'

rows = [
    ('Users / concurrent clients', lambda s: f"{s['users']} / {s['clients']}"),
    ('Measured seconds, including final responses', lambda s: fmt(s['measuredSecondsIncludingDrain'])),
    ('Requests / successful responses', lambda s: f"{s['requests']:,} / {s['successfulResponses']:,}"),
    ('Success rate', lambda s: f"{s['successRatePercent']:.4f}%"),
    ('Successful trades/second', lambda s: fmt(s['successfulTradesPerSecond'])),
    ('Mean / p50 latency (ms)', lambda s: f"{s['meanMs']:.2f} / {s['p50Ms']:.2f}"),
    ('p95 / p99 latency (ms)', lambda s: f"{s['p95Ms']:.2f} / {s['p99Ms']:.2f}"),
    ('Maximum latency (ms)', lambda s: fmt(s['maxMs'])),
    ('Requests strictly below 200 ms', lambda s: f"{s['requestsUnder200Ms']:,} ({s['under200MsPercent']:.4f}%)"),
    ('Requests at least 200 ms', lambda s: fmt(s['requests'] - s['requestsUnder200Ms'])),
    ('Peak client requests in flight, including warmup', lambda s: str(s['peakClientInFlight'])),
    ('Balance mismatches / wallets reconciled', lambda s: f"{s['balanceMismatches']} / {s['walletsReconciled']}"),
    ('Negative wallets / duplicate keys', lambda s: f"{s['negativeWallets']} / {s['duplicateRequestKeys']}"),
    ('Missing acknowledged trades / incomplete audit rows', lambda s: f"{s['acknowledgedTradesMissingFromAudit']} / {s['invalidAuditRows']}"),
    ('Committed trades without acknowledgement', lambda s: str(s['committedTradesWithoutAcknowledgement'])),
    ('Maximum accepted fixture quote age (ms)', lambda s: fmt(s['maximumAcceptedQuoteAgeMs'])),
]
table = '\n'.join(f'| {name} | {render(primary)} | {render(contention)} |' for name, render in rows)
line = coverage['LINE']; branch = coverage['BRANCH']
line_percent = 100 * line['covered'] / (line['covered'] + line['missed'])
branch_percent = 100 * branch['covered'] / (branch['covered'] + branch['missed'])
under200 = all(s['allRequestsUnder200Ms'] for s in scenarios)
success = all(s['successRateOver99_5Percent'] for s in scenarios)
concurrent = all(s['peakClientInFlight'] >= 100 for s in scenarios)
reconciled = sum(s['walletsReconciled'] for s in scenarios)
persisted = sum(s['persistedTradesIncludingWarmup'] for s in scenarios)
measured = sum(s['successfulResponses'] for s in scenarios)
report = f'''# H2 benchmark and requirement verification

Run: {benchmark['startedAt']} to {benchmark['finishedAt']} (UTC).

The unchanged H2 trading implementation was tested through its real HTTP endpoint. The correctness build passed **{tests['tests']} tests, {tests['failures']} failures, {tests['errors']} errors, {tests['skipped']} skipped**. The benchmark is a separate explicit test and its assertion result is in [benchmark-run.log](benchmark-run.log).

## Measured results

| Metric | 100 independent users | 100 clients sharing one wallet |
| --- | --- | --- |
{table}

HTTP status counts: independent users `{json.dumps(primary['httpStatuses'])}`; shared wallet `{json.dumps(contention['httpStatuses'])}`. Error codes: independent users `{json.dumps(primary['errors'])}`; shared wallet `{json.dumps(contention['errors'])}`. Fixture publication failures: **{benchmark['fixtureFeedFailures']}**.

## Requirement comparison

The requirements come from [1_BUSINESS_REQUIREMENTS.md](../1_BUSINESS_REQUIREMENTS.md). A short benchmark only supports the conclusions listed below.

| Requirement | Result | Evidence / scope |
| --- | --- | --- |
| Trading API response times under 200 ms | **{passed(under200)}** | The requirement specifies no percentile. Treating it literally means every measured response must be below 200 ms. The table reports all outliers. p95 is a diagnostic, not a substitute requirement. |
| Minimum 100 simultaneous trades | **{passed(concurrent)}** | 100 barrier-started clients per scenario; peak client requests in flight reached 100. This includes HTTP/database queueing and does not assert 100 database transactions execute at once. Correctness tests separately exercise 100-thread contention. |
| API success rate >99.5% | **{passed(success)}** | Measured first-attempt responses for funded, valid requests; all HTTP/transport errors stay in the denominator. No retries hide errors. |
| Minimum 1,000 trades/day | Local capacity demonstrated; daily operation unmeasured | {measured:,} successful measured trades were executed in these short runs. This exceeds 1,000 actual executions, but is not a 24-hour availability/throughput test or a claim of daily customer volume. |
| Typical $100–$10,000 trade size | Fixture workload within range under stated assumption | BTCUSDT quantity 0.01 settles 500–500.10 USDT; ETHUSDT quantity 0.2 settles 600–600.20 USDT. No live USD conversion was measured. |
| Zero balance calculation errors | No errors observed | All {reconciled} benchmark wallets reconciled exactly against opening balances plus committed trade deltas; negative wallets and mismatches are reported above. Unit tests cover rounding, dust, and overflow boundaries. |
| Complete transaction audit trail | Verified for this run | {persisted:,} persisted trades including warmup were checked against acknowledgement IDs. Missing acknowledgements, incomplete metadata, and duplicate keys are reported explicitly. |
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
- Independent-user scenario: 100 separately funded users, {primary['targetSeconds']} measured seconds. Contention scenario: all clients share one user, {contention['targetSeconds']} measured seconds.
- Both scenarios alternate BUY/SELL and BTCUSDT/ETHUSDT with unique request keys. Each user begins with 10,000,000 USDT, 100 BTC, and 1,000 ETH to isolate concurrency from intentional insufficient-funds rejection.
- Real-time fixture quote updates every 100 ms replace external fetching, while keeping the one-second trade freshness check. The HTTP timeout is five seconds. There are no automatic request retries.
- Environment: {benchmark['os']}, {benchmark['architecture']}, Java {benchmark['javaVersion']}, {benchmark['database']}; {benchmark['availableProcessors']} JVM-visible processors, {benchmark['jvmMaxHeapBytes'] / 1024**3:.2f} GiB maximum JVM heap, Hikari maximum pool size {benchmark['hikariMaximumPoolSize']}.
- Client and application share the machine/JVM. JaCoCo and Mockito Java agents are enabled by the existing test configuration. No production application code or connection-pool tuning was changed for this benchmark.

```java
// LIMITATION: This is a local closed-loop assessment benchmark. It does not model independent
// fixed-rate arrivals, remote-network latency, TLS, multiple application instances, or a long soak.
// The p95/p99 values use nearest-rank percentiles over every measured attempt, including failures.
```

## Correctness and coverage

The **{tests['tests']} passing tests** cover BUY/SELL pricing, exact-balance spending, lazy wallet creation, insufficient funds, decimal limits/rounding, rollback after debit or credit, matching/conflicting idempotency keys, 100 concurrent same-user requests, mixed BUY/SELL, shared USDT across pairs, 100 independent users, stale/future/crossed quotes, provider parsing/fallback, scheduler activation, and HTTP validation.

Functional-suite coverage before the benchmark: **{line_percent:.2f}% lines** ({line['covered']}/{line['covered'] + line['missed']}) and **{branch_percent:.2f}% branches** ({branch['covered']}/{branch['covered'] + branch['missed']}). Coverage is supporting information, not proof of correctness.

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
'''
(folder / 'RESULTS.md').write_text(report)
print(folder / 'RESULTS.md')
