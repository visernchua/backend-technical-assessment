package com.aquariux.technical.assessment.trade.pricing;

import java.math.BigDecimal;
import java.time.LocalDateTime;

public record MarketQuote(
        String pair,
        BigDecimal bid,
        BigDecimal ask,
        String source,
        LocalDateTime receivedAt,
        LocalDateTime providerAt) {}
