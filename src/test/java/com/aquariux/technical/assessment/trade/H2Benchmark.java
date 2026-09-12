package com.aquariux.technical.assessment.trade;

import static org.assertj.core.api.Assertions.assertThat;

import com.aquariux.technical.assessment.trade.entity.CryptoPrice;
import com.aquariux.technical.assessment.trade.mapper.CryptoPriceMapper;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.io.*;
import java.math.BigDecimal;
import java.net.URI;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.zip.GZIPOutputStream;

// REASON: Explicitly run with -Dtest=H2Benchmark; sustained timing checks do not belong in the
// default correctness suite. No application settings, database vendor, or new dependencies are
// needed.
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties =
                "spring.datasource.url=jdbc:h2:mem:benchmark;DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=1000")
@ActiveProfiles("test")
class H2Benchmark {
    @Autowired JdbcTemplate jdbc;
    @Autowired CryptoPriceMapper prices;
    @Autowired ObjectMapper json;
    @LocalServerPort int port;
    private static final int CLIENTS = 100;
    private static final BigDecimal INITIAL_USDT = new BigDecimal("10000000");
    private static final BigDecimal INITIAL_BTC = new BigDecimal("100");
    private static final BigDecimal INITIAL_ETH = new BigDecimal("1000");
    private final AtomicInteger feedFailures = new AtomicInteger();

    record Sample(boolean measured, long latencyNanos, int status, String code, Long tradeId) {}

    @Test
    void benchmark() throws Exception {
        Files.createDirectories(Path.of("verification"));
        var started = Instant.now();
        var scenarios = new ArrayList<Map<String, Object>>();
        // ASSUMPTION: Deterministic prices isolate settlement performance. Real Clock timestamps
        // are refreshed every 100 ms, so normal one-second staleness checks are not bypassed.
        try (var feed = Executors.newSingleThreadScheduledExecutor()) {
            publishQuotes();
            feed.scheduleWithFixedDelay(
                    () -> {
                        try {
                            publishQuotes();
                        } catch (Exception ex) {
                            feedFailures.incrementAndGet();
                        }
                    },
                    100,
                    100,
                    TimeUnit.MILLISECONDS);
            scenarios.add(
                    run(
                            "independent-users",
                            10000,
                            CLIENTS,
                            Long.getLong("benchmark.seconds", 60L)));
            scenarios.add(
                    run(
                            "shared-wallet",
                            20000,
                            1,
                            Long.getLong("benchmark.contention.seconds", 15L)));
        }
        Map<String, Object> report = new LinkedHashMap<>();
        report.put("startedAt", started.toString());
        report.put("finishedAt", Instant.now().toString());
        report.put("javaVersion", System.getProperty("java.version"));
        report.put("os", System.getProperty("os.name") + " " + System.getProperty("os.version"));
        report.put("architecture", System.getProperty("os.arch"));
        report.put("availableProcessors", Runtime.getRuntime().availableProcessors());
        report.put("jvmMaxHeapBytes", Runtime.getRuntime().maxMemory());
        try (var connection = jdbc.getDataSource().getConnection()) {
            report.put(
                    "database",
                    connection.getMetaData().getDatabaseProductName()
                            + " "
                            + connection.getMetaData().getDatabaseProductVersion());
        }
        report.put(
                "hikariMaximumPoolSize",
                ((com.zaxxer.hikari.HikariDataSource) jdbc.getDataSource()).getMaximumPoolSize());
        report.put("fixtureFeedFailures", feedFailures.get());
        report.put("fixtureRefreshMs", 100);
        report.put("requestTimeoutSeconds", 5);
        report.put(
                "workload",
                "Equal BUY/SELL alternation across BTCUSDT (0.01 BTC) and ETHUSDT (0.2 ETH),"
                    + " approximately 500-600 USDT each");
        report.put(
                "loadModel",
                "100 barrier-started closed-loop HTTP/1.1 clients, no automatic retries,"
                    + " five-second warmup per scenario");
        report.put(
                "limitations",
                List.of(
                        "Loopback client/server share this machine and JVM; Java agents remain"
                            + " enabled",
                        "Controlled prices; no exchange network latency or end-to-end market-event"
                            + " freshness measured",
                        "Pre-authenticated assessment security; no identity provider or TLS"
                            + " measurement",
                        "Short run cannot establish monthly uptime, a 24-hour service level, or"
                            + " restart durability of in-memory H2",
                        "Closed-loop latency includes client HTTP queueing but does not model an"
                            + " independent fixed arrival rate"));
        report.put("scenarios", scenarios);
        json.writerWithDefaultPrettyPrinter()
                .writeValue(Path.of("verification/benchmark-results.json").toFile(), report);
        // REASON: Persist slow requests and errors, rather than changing thresholds until the run
        // passes.
        // These assertions check financial integrity; requirement comparisons are reported
        // separately.
        for (var scenario : scenarios) {
            assertThat(((Number) scenario.get("balanceMismatches")).intValue()).isZero();
            assertThat(((Number) scenario.get("negativeWallets")).intValue()).isZero();
            assertThat(((Number) scenario.get("duplicateRequestKeys")).intValue()).isZero();
            assertThat(((Number) scenario.get("invalidAuditRows")).intValue()).isZero();
            assertThat(((Number) scenario.get("acknowledgedTradesMissingFromAudit")).intValue())
                    .isZero();
        }
    }

    private Map<String, Object> run(String name, long firstUser, int users, long seconds)
            throws Exception {
        if (seconds < 1 || seconds > 1800)
            throw new IllegalArgumentException("Benchmark duration must be 1-1800 seconds");
        for (long id = firstUser; id < firstUser + users; id++) {
            jdbc.update(
                    "INSERT INTO users(id,username,email,password) VALUES(?,?,?,?)",
                    id,
                    "bench" + id,
                    id + "@benchmark.invalid",
                    "unused");
            for (int symbol = 1; symbol <= 3; symbol++)
                jdbc.update(
                        "INSERT INTO user_wallets(user_id,symbol_id,balance) VALUES(?,?,?)",
                        id,
                        symbol,
                        opening(symbol));
        }
        var samples = new ConcurrentLinkedQueue<Sample>();
        var active = new AtomicInteger();
        var peak = new AtomicInteger();
        var ready = new CountDownLatch(CLIENTS);
        var start = new CountDownLatch(1);
        long[] boundaries = new long[2];
        long completedAt;
        try (var client =
                        HttpClient.newBuilder()
                                .version(HttpClient.Version.HTTP_1_1)
                                .connectTimeout(Duration.ofSeconds(5))
                                .build();
                var workers = Executors.newVirtualThreadPerTaskExecutor()) {
            var futures = new ArrayList<Future<?>>();
            for (int i = 0; i < CLIENTS; i++) {
                int index = i;
                futures.add(
                        workers.submit(
                                () -> {
                                    ready.countDown();
                                    start.await();
                                    int sequence = 0;
                                    while (System.nanoTime() < boundaries[1]) {
                                        long user = firstUser + (index % users);
                                        boolean btc = (index + sequence / 2) % 2 == 0;
                                        boolean buy = sequence % 2 == 0;
                                        String body =
                                                "{\"userId\":"
                                                        + user
                                                        + ",\"pairName\":\""
                                                        + (btc ? "BTCUSDT" : "ETHUSDT")
                                                        + "\",\"tradeType\":\""
                                                        + (buy ? "BUY" : "SELL")
                                                        + "\",\"quantity\":\""
                                                        + (btc ? "0.01" : "0.2")
                                                        + "\"}";
                                        var request =
                                                HttpRequest.newBuilder(
                                                                URI.create(
                                                                        "http://localhost:"
                                                                                + port
                                                                                + "/api/trades/execute"))
                                                        .timeout(Duration.ofSeconds(5))
                                                        .header("Content-Type", "application/json")
                                                        .header(
                                                                "Idempotency-Key",
                                                                name
                                                                        + "-"
                                                                        + index
                                                                        + "-"
                                                                        + sequence++)
                                                        .POST(
                                                                HttpRequest.BodyPublishers.ofString(
                                                                        body))
                                                        .build();
                                        long begin = System.nanoTime();
                                        boolean measured = begin >= boundaries[0];
                                        int status = 0;
                                        String code = "";
                                        Long tradeId = null;
                                        peak.accumulateAndGet(active.incrementAndGet(), Math::max);
                                        long finished;
                                        String responseBody = null;
                                        try {
                                            var response =
                                                    client.send(
                                                            request,
                                                            HttpResponse.BodyHandlers.ofString());
                                            status = response.statusCode();
                                            responseBody = response.body();
                                        } catch (Exception ex) {
                                            code = ex.getClass().getSimpleName();
                                            if (ex instanceof InterruptedException) {
                                                Thread.currentThread().interrupt();
                                                throw ex;
                                            }
                                        } finally {
                                            finished = System.nanoTime();
                                            active.decrementAndGet();
                                        }
                                        if (responseBody != null) {
                                            try {
                                                var decoded = json.readTree(responseBody);
                                                if (status == 200 && decoded.has("tradeId"))
                                                    tradeId = decoded.get("tradeId").asLong();
                                                else
                                                    code =
                                                            decoded.path("code")
                                                                    .asText("UNKNOWN_ERROR");
                                            } catch (Exception ex) {
                                                code = "INVALID_RESPONSE";
                                            }
                                        }
                                        samples.add(
                                                new Sample(
                                                        measured,
                                                        finished - begin,
                                                        status,
                                                        code,
                                                        tradeId));
                                    }
                                    return null;
                                }));
            }
            if (!ready.await(10, TimeUnit.SECONDS))
                throw new IllegalStateException("Clients did not become ready");
            boundaries[0] = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            boundaries[1] = boundaries[0] + TimeUnit.SECONDS.toNanos(seconds);
            start.countDown();
            for (var future : futures) future.get(seconds + 20, TimeUnit.SECONDS);
            completedAt = System.nanoTime();
        }
        var measured = samples.stream().filter(Sample::measured).toList();
        var latencies = measured.stream().mapToLong(Sample::latencyNanos).sorted().toArray();
        if (latencies.length == 0) throw new IllegalStateException("No measured requests");
        long successes =
                measured.stream().filter(s -> s.status() == 200 && s.tradeId() != null).count();
        var statuses = new TreeMap<String, Long>();
        var errors = new TreeMap<String, Long>();
        for (var sample : measured) {
            statuses.merge(String.valueOf(sample.status()), 1L, Long::sum);
            if (!sample.code().isEmpty()) errors.merge(sample.code(), 1L, Long::sum);
        }
        double elapsed = (completedAt - boundaries[0]) / 1e9;
        long under200 = Arrays.stream(latencies).filter(n -> n < 200_000_000L).count();
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("scenario", name);
        result.put("users", users);
        result.put("clients", CLIENTS);
        result.put("peakClientInFlight", peak.get());
        result.put("warmupSeconds", 5);
        result.put("targetSeconds", seconds);
        result.put("measuredSecondsIncludingDrain", elapsed);
        result.put("requests", measured.size());
        result.put("successfulResponses", successes);
        result.put("successRatePercent", successes * 100.0 / measured.size());
        result.put("successfulTradesPerSecond", successes / elapsed);
        result.put("meanMs", Arrays.stream(latencies).average().orElseThrow() / 1e6);
        result.put("p50Ms", percentile(latencies, .50));
        result.put("p95Ms", percentile(latencies, .95));
        result.put("p99Ms", percentile(latencies, .99));
        result.put("maxMs", latencies[latencies.length - 1] / 1e6);
        result.put("requestsUnder200Ms", under200);
        result.put("under200MsPercent", under200 * 100.0 / measured.size());
        result.put("httpStatuses", statuses);
        result.put("errors", errors);
        result.put("allRequestsUnder200Ms", under200 == measured.size());
        result.put("p95Under200Ms", percentile(latencies, .95) < 200);
        result.put("successRateOver99_5Percent", successes * 100.0 / measured.size() > 99.5);
        reconcile(result, firstUser, users, samples);
        writeSamples(name, samples);
        System.out.println("BENCHMARK " + json.writeValueAsString(result));
        return result;
    }

    private void reconcile(
            Map<String, Object> result, long first, int users, Collection<Sample> samples) {
        long last = first + users;
        var stored =
                new HashSet<>(
                        jdbc.queryForList(
                                "SELECT id FROM trades WHERE user_id>=? AND user_id<?",
                                Long.class,
                                first,
                                last));
        var acknowledged = new HashSet<Long>();
        for (var sample : samples)
            if (sample.status() == 200 && sample.tradeId() != null)
                acknowledged.add(sample.tradeId());
        result.put("persistedTradesIncludingWarmup", stored.size());
        result.put("acknowledgedUniqueTradesIncludingWarmup", acknowledged.size());
        result.put(
                "acknowledgedTradesMissingFromAudit",
                acknowledged.stream().filter(id -> !stored.contains(id)).count());
        result.put(
                "committedTradesWithoutAcknowledgement",
                stored.stream().filter(id -> !acknowledged.contains(id)).count());
        int mismatches = 0;
        for (long user = first; user < last; user++)
            for (int symbol = 1; symbol <= 3; symbol++) {
                String delta =
                        symbol == 3
                                ? "CASE WHEN trade_type='SELL' THEN total_amount ELSE -total_amount"
                                      + " END"
                                : "CASE WHEN trade_type='BUY' THEN quantity ELSE -quantity END";
                String pair = symbol == 3 ? "" : " AND crypto_pair_id=" + symbol;
                BigDecimal change =
                        jdbc.queryForObject(
                                "SELECT COALESCE(SUM("
                                        + delta
                                        + "),0) FROM trades WHERE user_id=?"
                                        + pair,
                                BigDecimal.class,
                                user);
                BigDecimal actual =
                        jdbc.queryForObject(
                                "SELECT balance FROM user_wallets WHERE user_id=? AND symbol_id=?",
                                BigDecimal.class,
                                user,
                                symbol);
                if (actual.compareTo(opening(symbol).add(change)) != 0) mismatches++;
            }
        result.put("balanceMismatches", mismatches);
        result.put("walletsReconciled", users * 3);
        result.put(
                "negativeWallets",
                jdbc.queryForObject(
                        "SELECT COUNT(*) FROM user_wallets WHERE user_id>=? AND user_id<? AND"
                            + " balance<0",
                        Integer.class,
                        first,
                        last));
        result.put(
                "duplicateRequestKeys",
                jdbc.queryForObject(
                        "SELECT COUNT(*) FROM (SELECT user_id,idempotency_key FROM trades WHERE"
                            + " user_id>=? AND user_id<? GROUP BY user_id,idempotency_key HAVING"
                            + " COUNT(*)>1)",
                        Integer.class,
                        first,
                        last));
        result.put(
                "invalidAuditRows",
                jdbc.queryForObject(
                        "SELECT COUNT(*) FROM trades WHERE user_id>=? AND user_id<? AND"
                            + " (idempotency_key IS NULL OR price_snapshot_id IS NULL OR"
                            + " price_source IS NULL OR trade_time IS NULL OR quantity<=0 OR"
                            + " total_amount<=0)",
                        Integer.class,
                        first,
                        last));
        result.put(
                "maximumAcceptedQuoteAgeMs",
                jdbc.queryForObject(
                        "SELECT MAX(DATEDIFF('MICROSECOND',price_observed_at,trade_time))/1000.0"
                            + " FROM trades WHERE user_id>=? AND user_id<?",
                        Double.class,
                        first,
                        last));
    }

    private static double percentile(long[] sorted, double fraction) {
        return sorted[(int) Math.ceil(sorted.length * fraction) - 1] / 1e6;
    }

    private static BigDecimal opening(int symbol) {
        return symbol == 1 ? INITIAL_BTC : symbol == 2 ? INITIAL_ETH : INITIAL_USDT;
    }

    private void publishQuotes() {
        for (long pair = 1; pair <= 2; pair++) {
            var price = new CryptoPrice();
            price.setCryptoPairId(pair);
            price.setBidPrice(new BigDecimal(pair == 1 ? "50000" : "3000"));
            price.setAskPrice(new BigDecimal(pair == 1 ? "50010" : "3001"));
            var now = LocalDateTime.ofInstant(Instant.now(), ZoneOffset.UTC);
            price.setCreatedAt(now);
            price.setBidReceivedAt(now);
            price.setAskReceivedAt(now);
            price.setBidSource("BINANCE");
            price.setAskSource("HUOBI");
            price.setEligible(true);
            prices.insertPrice(price);
        }
    }

    private static void writeSamples(String name, Collection<Sample> samples) throws IOException {
        try (var output =
                new BufferedWriter(
                        new OutputStreamWriter(
                                new GZIPOutputStream(
                                        Files.newOutputStream(
                                                Path.of(
                                                        "verification/"
                                                                + name
                                                                + "-samples.csv.gz"))),
                                StandardCharsets.UTF_8))) {
            output.write("measured,latency_ns,http_status,error_code,trade_id\n");
            for (var s : samples)
                output.write(
                        s.measured()
                                + ","
                                + s.latencyNanos()
                                + ","
                                + s.status()
                                + ","
                                + s.code()
                                + ","
                                + (s.tradeId() == null ? "" : s.tradeId())
                                + "\n");
        }
    }
}
