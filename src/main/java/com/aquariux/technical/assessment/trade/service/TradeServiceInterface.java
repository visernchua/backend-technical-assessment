package com.aquariux.technical.assessment.trade.service;

import com.aquariux.technical.assessment.trade.dto.request.TradeRequest;
import com.aquariux.technical.assessment.trade.dto.response.TradeResponse;

public interface TradeServiceInterface {
    TradeResponse executeTrade(TradeRequest request, String idempotencyKey);
}
