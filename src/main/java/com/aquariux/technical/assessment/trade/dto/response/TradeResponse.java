package com.aquariux.technical.assessment.trade.dto.response;

import java.math.BigDecimal;
import java.time.Instant;

// D02: Only immutable execution facts are returned, so a replay never changes with wallet balances.
public record TradeResponse(
        Long tradeId,
        Long userId,
        String pairName,
        String tradeType,
        BigDecimal quantity,
        BigDecimal price,
        BigDecimal totalAmount,
        String priceSource,
        Long priceSnapshotId,
        Instant priceObservedAt,
        Instant tradeTime) {}
