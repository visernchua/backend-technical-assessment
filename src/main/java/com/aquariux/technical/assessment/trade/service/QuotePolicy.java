package com.aquariux.technical.assessment.trade.service;

import com.aquariux.technical.assessment.trade.entity.CryptoPrice;
import com.aquariux.technical.assessment.trade.enums.TradeType;
import com.aquariux.technical.assessment.trade.exception.TradeException;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

import java.time.*;
import java.util.Set;

@Component
public class QuotePolicy {
    private final MonetaryPolicy money;
    private final long maxAgeMillis;

    public QuotePolicy(
            MonetaryPolicy money, @Value("${trading.max-price-age-ms:1000}") long maxAgeMillis) {
        if (maxAgeMillis <= 0 || maxAgeMillis > 1000)
            throw new IllegalArgumentException("Quote age must be in (0, 1000] ms");
        this.money = money;
        this.maxAgeMillis = maxAgeMillis;
    }

    public void validate(CryptoPrice price, TradeType side, Instant now) {
        if (price == null) fail("PRICE_UNAVAILABLE", "No current price is available");
        if (!Boolean.TRUE.equals(price.getEligible())
                || !money.positiveRepresentable(price.getBidPrice())
                || !money.positiveRepresentable(price.getAskPrice())
                || price.getBidPrice().compareTo(price.getAskPrice()) > 0
                || !Set.of("BINANCE", "HUOBI")
                        .contains(price.getBidSource() == null ? "" : price.getBidSource())
                || !Set.of("BINANCE", "HUOBI")
                        .contains(price.getAskSource() == null ? "" : price.getAskSource()))
            fail("PRICE_INVALID", "The current quote is not executable");
        var received = side == TradeType.BUY ? price.getAskReceivedAt() : price.getBidReceivedAt();
        var event = side == TradeType.BUY ? price.getAskProviderAt() : price.getBidProviderAt();
        // A09: Receipt/provider-response age is a freshness proxy, not a market-event guarantee.
        // Future timestamps fail closed.
        if (!fresh(received, now) || (event != null && !fresh(event, now)))
            fail("PRICE_STALE", "The selected price is expired or has an invalid timestamp");
    }

    public boolean fresh(LocalDateTime timestamp, Instant now) {
        if (timestamp == null) return false;
        Duration age = Duration.between(timestamp.toInstant(ZoneOffset.UTC), now);
        return !age.isNegative() && age.compareTo(Duration.ofMillis(maxAgeMillis)) < 0;
    }

    private static void fail(String code, String message) {
        throw new TradeException(HttpStatus.SERVICE_UNAVAILABLE, code, message);
    }
}
