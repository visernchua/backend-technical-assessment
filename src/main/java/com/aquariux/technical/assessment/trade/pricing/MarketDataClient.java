package com.aquariux.technical.assessment.trade.pricing;

import com.aquariux.technical.assessment.trade.service.MonetaryPolicy;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

import java.math.BigDecimal;
import java.net.URI;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;

@Component
@RequiredArgsConstructor
@Slf4j
public class MarketDataClient {
    private final RestTemplate restTemplate;
    private final Clock clock;
    private final MonetaryPolicy money;
    private final Map<String, Instant> retryAfter = new ConcurrentHashMap<>();
    private final Map<String, Integer> failures = new ConcurrentHashMap<>();

    @Value(
            "${trading.pricing.binance-url:https://api.binance.com/api/v3/ticker/bookTicker?symbols=%5B%22BTCUSDT%22,%22ETHUSDT%22%5D}")
    private String binanceUrl;

    @Value("${trading.pricing.huobi-url:https://api.huobi.pro/market/tickers}")
    private String huobiUrl;

    public List<MarketQuote> fetch(String source) {
        if (clock.instant().isBefore(retryAfter.getOrDefault(source, Instant.MIN)))
            return List.of();
        try {
            List<MarketQuote> result = new ArrayList<>();
            if (source.equals("BINANCE")) {
                BinanceTicker[] tickers =
                        restTemplate.getForObject(URI.create(binanceUrl), BinanceTicker[].class);
                LocalDateTime received = utcNow();
                if (tickers != null)
                    for (var t : tickers)
                        add(result, t.symbol(), t.bidPrice(), t.askPrice(), source, received, null);
            } else {
                HuobiResponse response =
                        restTemplate.getForObject(URI.create(huobiUrl), HuobiResponse.class);
                LocalDateTime received = utcNow();
                if (response != null && "ok".equals(response.status()) && response.data() != null) {
                    // REASON: Huobi ts is response-generation time, not the last order-book event.
                    // Both providers therefore offer only freshness proxies through these
                    // endpoints.
                    var event =
                            response.ts() == null
                                    ? null
                                    : LocalDateTime.ofInstant(
                                            Instant.ofEpochMilli(response.ts()), ZoneOffset.UTC);
                    for (var t : response.data())
                        add(
                                result,
                                t.symbol() == null ? null : t.symbol().toUpperCase(Locale.ROOT),
                                t.bid(),
                                t.ask(),
                                source,
                                received,
                                event);
                }
            }
            if (result.isEmpty()) throw new IllegalStateException("No valid supported quotes");
            failures.remove(source);
            retryAfter.remove(source);
            return result;
        } catch (Exception ex) {
            // A10: Failure of one source cannot discard the other source's valid quotes.
            int count = failures.merge(source, 1, (a, b) -> Math.min(a + b, 6));
            long delay =
                    Math.min(5000, 250L * (1L << count))
                            + ThreadLocalRandom.current().nextLong(100);
            retryAfter.put(source, clock.instant().plusMillis(delay));
            log.warn("Price fetch failed source={} type={}", source, ex.getClass().getSimpleName());
            return List.of();
        }
    }

    private void add(
            List<MarketQuote> result,
            String pair,
            String bidText,
            String askText,
            String source,
            LocalDateTime received,
            LocalDateTime event) {
        if (pair == null
                || !Set.of("BTCUSDT", "ETHUSDT").contains(pair)
                || bidText == null
                || askText == null
                || bidText.length() > 64
                || askText.length() > 64) return;
        // REASON: Parse each ticker separately and directly from its decimal text; one malformed
        // supported pair must not discard another valid pair from the same provider response.
        try {
            var bid = new BigDecimal(bidText);
            var ask = new BigDecimal(askText);
            if (money.positiveRepresentable(bid)
                    && money.positiveRepresentable(ask)
                    && bid.compareTo(ask) <= 0)
                result.add(new MarketQuote(pair, bid, ask, source, received, event));
        } catch (NumberFormatException ex) {
            log.warn("Ignoring malformed ticker pair={} source={}", pair, source);
        }
    }

    private LocalDateTime utcNow() {
        return LocalDateTime.ofInstant(clock.instant(), ZoneOffset.UTC);
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record BinanceTicker(String symbol, String bidPrice, String askPrice) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record HuobiTicker(String symbol, String bid, String ask) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record HuobiResponse(String status, Long ts, List<HuobiTicker> data) {}
}
