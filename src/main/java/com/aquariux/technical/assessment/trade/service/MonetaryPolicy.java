package com.aquariux.technical.assessment.trade.service;

import com.aquariux.technical.assessment.trade.exception.TradeException;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

import java.math.*;

@Component
public class MonetaryPolicy {
    public static final BigDecimal MAX = new BigDecimal("999999999999.99999999");
    public static final BigDecimal UNIT = new BigDecimal("0.00000001");

    public BigDecimal quantity(BigDecimal value) {
        if (!positiveRepresentable(value))
            throw new TradeException(
                    HttpStatus.BAD_REQUEST,
                    "INVALID_QUANTITY",
                    "Quantity must fit DECIMAL(20,8) and be positive");
        return value.setScale(8, RoundingMode.UNNECESSARY);
    }

    public boolean positiveRepresentable(BigDecimal value) {
        if (value == null
                || value.signum() <= 0
                || value.compareTo(MAX) > 0
                || value.stripTrailingZeros().scale() > 8) return false;
        try {
            value.setScale(8, RoundingMode.UNNECESSARY);
            return true;
        } catch (ArithmeticException ex) {
            return false;
        }
    }

    public BigDecimal notional(BigDecimal quantity, BigDecimal price) {
        // A05: Round exactly once, HALF_EVEN. Reject dust before rounding so no free transfers
        // occur.
        BigDecimal raw = quantity.multiply(price);
        if (raw.compareTo(UNIT) < 0 || raw.compareTo(MAX) > 0)
            throw new TradeException(
                    HttpStatus.UNPROCESSABLE_ENTITY,
                    "NOTIONAL_OUT_OF_RANGE",
                    "Settlement amount is outside supported limits");
        return raw.setScale(8, RoundingMode.HALF_EVEN);
    }
}
