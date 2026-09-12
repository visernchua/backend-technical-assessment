package com.aquariux.technical.assessment.trade.pricing;

import com.aquariux.technical.assessment.trade.entity.CryptoPrice;
import com.aquariux.technical.assessment.trade.mapper.*;
import com.aquariux.technical.assessment.trade.service.QuotePolicy;

import lombok.RequiredArgsConstructor;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.*;
import java.util.*;

@Service
@RequiredArgsConstructor
public class PricePublisher {
    private final CryptoPairMapper pairs;
    private final CryptoPriceMapper prices;
    private final QuotePolicy policy;
    private final Clock clock;

    // ASSUMPTION: One application instance uses the supplied in-memory H2 database.
    // REASON: fixedDelay scheduling does not overlap runs; no distributed lease is needed.
    @Transactional(timeout = 2, rollbackFor = Exception.class)
    public int publish(List<MarketQuote> quotes) {
        Instant now = clock.instant();
        int inserted = 0;
        for (String pair : List.of("BTCUSDT", "ETHUSDT")) {
            var valid =
                    quotes.stream()
                            .filter(
                                    q ->
                                            pair.equals(q.pair())
                                                    && policy.fresh(q.receivedAt(), now)
                                                    && (q.providerAt() == null
                                                            || policy.fresh(q.providerAt(), now)))
                            .toList();
            if (valid.isEmpty()) continue;
            var bid =
                    valid.stream()
                            .sorted(
                                    Comparator.comparing(MarketQuote::bid)
                                            .reversed()
                                            .thenComparing(MarketQuote::source))
                            .findFirst()
                            .orElseThrow();
            var ask =
                    valid.stream()
                            .sorted(
                                    Comparator.comparing(MarketQuote::ask)
                                            .thenComparing(MarketQuote::source))
                            .findFirst()
                            .orElseThrow();
            Long pairId =
                    pairs.findIdByPairName(
                            pair); // REASON: Resolve this actual pair, never substitute BTC/ETH.
            if (pairId == null) continue;
            var previous = prices.findExecutionPrice(pairId);
            // REASON: Neither side may regress in observation time when a delayed provider response
            // arrives.
            if (previous != null
                    && (observed(bid)
                                    .isBefore(
                                            observed(
                                                    previous.getBidProviderAt(),
                                                    previous.getBidReceivedAt()))
                            || observed(ask)
                                    .isBefore(
                                            observed(
                                                    previous.getAskProviderAt(),
                                                    previous.getAskReceivedAt())))) continue;
            CryptoPrice snapshot = new CryptoPrice();
            snapshot.setCryptoPairId(pairId);
            snapshot.setBidPrice(bid.bid());
            snapshot.setAskPrice(ask.ask());
            snapshot.setBidSource(bid.source());
            snapshot.setAskSource(ask.source());
            snapshot.setBidReceivedAt(bid.receivedAt());
            snapshot.setAskReceivedAt(ask.receivedAt());
            snapshot.setBidProviderAt(bid.providerAt());
            snapshot.setAskProviderAt(ask.providerAt());
            snapshot.setCreatedAt(LocalDateTime.ofInstant(now, ZoneOffset.UTC));
            // A11: Preserve crossed observations for audit, but make the newest snapshot
            // non-executable.
            snapshot.setEligible(bid.bid().compareTo(ask.ask()) <= 0);
            prices.insertPrice(snapshot);
            inserted++;
        }
        return inserted;
    }

    private static LocalDateTime observed(MarketQuote quote) {
        return observed(quote.providerAt(), quote.receivedAt());
    }

    private static LocalDateTime observed(LocalDateTime event, LocalDateTime received) {
        return event == null ? received : event;
    }
}
