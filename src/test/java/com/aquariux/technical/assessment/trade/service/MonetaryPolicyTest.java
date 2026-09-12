package com.aquariux.technical.assessment.trade.service;

import static org.assertj.core.api.Assertions.*;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

class MonetaryPolicyTest {
    MonetaryPolicy money = new MonetaryPolicy();

    @Test
    void exactScaleAndBounds() {
        assertThat(money.quantity(new BigDecimal("0.00000001"))).isEqualByComparingTo("0.00000001");
        assertThat(money.quantity(MonetaryPolicy.MAX)).isEqualByComparingTo(MonetaryPolicy.MAX);
        for (String value :
                new String[] {"0", "-1", "0.000000001", "1000000000000", "1e-1000000000"})
            assertThatThrownBy(() -> money.quantity(new BigDecimal(value)))
                    .isInstanceOf(RuntimeException.class);
    }

    @Test
    void usesHalfEvenOnceAndRejectsDustAndOverflow() {
        assertThat(money.notional(new BigDecimal("0.00000001"), new BigDecimal("1.5")))
                .isEqualByComparingTo("0.00000002");
        assertThat(money.notional(new BigDecimal("0.00000001"), new BigDecimal("2.5")))
                .isEqualByComparingTo("0.00000002");
        assertThatThrownBy(
                        () -> money.notional(new BigDecimal("0.00000001"), new BigDecimal("0.5")))
                .hasMessageContaining("outside");
        assertThatThrownBy(() -> money.notional(MonetaryPolicy.MAX, new BigDecimal("2")))
                .hasMessageContaining("outside");
    }
}
