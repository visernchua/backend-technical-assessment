package com.aquariux.technical.assessment.trade.dto.request;

import com.aquariux.technical.assessment.trade.enums.TradeType;

import jakarta.validation.constraints.*;

import lombok.Data;

import java.math.BigDecimal;

@Data
public class TradeRequest {
    @NotNull @Positive private Long userId;

    @NotBlank
    @Pattern(regexp = "BTCUSDT|ETHUSDT")
    private String pairName;

    @NotNull private TradeType tradeType;

    // A02: Quantity is in base units; prices and quote amounts are computed by the server.
    @NotNull
    @DecimalMin(value = "0", inclusive = false)
    @Digits(integer = 12, fraction = 8)
    private BigDecimal quantity;
}
