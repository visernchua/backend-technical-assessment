package com.aquariux.technical.assessment.trade.service.impl;

import com.aquariux.technical.assessment.trade.dto.response.BestPriceResponse;
import com.aquariux.technical.assessment.trade.entity.CryptoPrice;
import com.aquariux.technical.assessment.trade.enums.TradeType;
import com.aquariux.technical.assessment.trade.exception.TradeException;
import com.aquariux.technical.assessment.trade.mapper.CryptoPriceMapper;
import com.aquariux.technical.assessment.trade.service.PriceServiceInterface;
import com.aquariux.technical.assessment.trade.service.QuotePolicy;

import lombok.RequiredArgsConstructor;

import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.ZoneOffset;
import java.util.List;

@Service
@RequiredArgsConstructor
public class PriceServiceImpl implements PriceServiceInterface {

    private final CryptoPriceMapper cryptoPriceMapper;
    private final QuotePolicy quotePolicy;
    private final Clock clock;

    @Override
    public List<BestPriceResponse> getLatestBestPrices() {
        List<CryptoPrice> latestPrices = cryptoPriceMapper.findLatestPrices();

        return latestPrices.stream().map(this::mapToResponse).toList();
    }

    private BestPriceResponse mapToResponse(CryptoPrice price) {
        BestPriceResponse response = new BestPriceResponse();
        response.setPairName(price.getPairName());
        response.setPriceSnapshotId(price.getId());
        var bidTime =
                price.getBidProviderAt() == null
                        ? price.getBidReceivedAt()
                        : price.getBidProviderAt();
        var askTime =
                price.getAskProviderAt() == null
                        ? price.getAskReceivedAt()
                        : price.getAskProviderAt();
        response.setBidObservedAt(bidTime == null ? null : bidTime.toInstant(ZoneOffset.UTC));
        response.setAskObservedAt(askTime == null ? null : askTime.toInstant(ZoneOffset.UTC));
        // REASON: Historical prices remain visible, but clients can distinguish them from
        // executable quotes.
        try {
            quotePolicy.validate(price, TradeType.BUY, clock.instant());
            quotePolicy.validate(price, TradeType.SELL, clock.instant());
            response.setExecutable(true);
        } catch (TradeException ignored) {
            response.setExecutable(false);
        }
        response.setBidPrice(price.getBidPrice());
        response.setAskPrice(price.getAskPrice());
        response.setBidSource(price.getBidSource());
        response.setAskSource(price.getAskSource());
        response.setTimestamp(price.getCreatedAt());
        return response;
    }
}
