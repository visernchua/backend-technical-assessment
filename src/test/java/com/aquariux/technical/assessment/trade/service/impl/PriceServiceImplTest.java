package com.aquariux.technical.assessment.trade.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import com.aquariux.technical.assessment.trade.dto.response.BestPriceResponse;
import com.aquariux.technical.assessment.trade.entity.CryptoPrice;
import com.aquariux.technical.assessment.trade.mapper.CryptoPriceMapper;
import com.aquariux.technical.assessment.trade.service.MonetaryPolicy;
import com.aquariux.technical.assessment.trade.service.QuotePolicy;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.List;

@ExtendWith(MockitoExtension.class)
class PriceServiceImplTest {

    @Mock private CryptoPriceMapper cryptoPriceMapper;

    @Spy private QuotePolicy quotePolicy = new QuotePolicy(new MonetaryPolicy(), 1000);

    @Spy private Clock clock = Clock.systemUTC();

    @InjectMocks private PriceServiceImpl priceService;

    private CryptoPrice btcPrice;
    private CryptoPrice ethPrice;

    @BeforeEach
    void setUp() {
        btcPrice = new CryptoPrice();
        btcPrice.setId(1L);
        btcPrice.setPairName("BTCUSDT");
        btcPrice.setBidPrice(new BigDecimal("50000.00"));
        btcPrice.setAskPrice(new BigDecimal("50100.00"));
        btcPrice.setBidSource("BINANCE");
        btcPrice.setAskSource("HUOBI");
        btcPrice.setCreatedAt(LocalDateTime.now());

        ethPrice = new CryptoPrice();
        ethPrice.setId(2L);
        ethPrice.setPairName("ETHUSDT");
        ethPrice.setBidPrice(new BigDecimal("3000.00"));
        ethPrice.setAskPrice(new BigDecimal("3050.00"));
        ethPrice.setBidSource("HUOBI");
        ethPrice.setAskSource("BINANCE");
        ethPrice.setCreatedAt(LocalDateTime.now());
    }

    @Test
    void getLatestBestPrices_ShouldReturnMappedPrices() {
        // Given
        List<CryptoPrice> mockPrices = Arrays.asList(btcPrice, ethPrice);
        when(cryptoPriceMapper.findLatestPrices()).thenReturn(mockPrices);

        // When
        List<BestPriceResponse> result = priceService.getLatestBestPrices();

        // Then
        assertThat(result).hasSize(2);

        BestPriceResponse btcResponse = result.get(0);
        assertThat(btcResponse.getPairName()).isEqualTo("BTCUSDT");
        assertThat(btcResponse.getBidPrice()).isEqualTo(new BigDecimal("50000.00"));
        assertThat(btcResponse.getAskPrice()).isEqualTo(new BigDecimal("50100.00"));
        assertThat(btcResponse.getBidSource()).isEqualTo("BINANCE");
        assertThat(btcResponse.getAskSource()).isEqualTo("HUOBI");
        assertThat(btcResponse.getTimestamp()).isEqualTo(btcPrice.getCreatedAt());

        BestPriceResponse ethResponse = result.get(1);
        assertThat(ethResponse.getPairName()).isEqualTo("ETHUSDT");
        assertThat(ethResponse.getBidPrice()).isEqualTo(new BigDecimal("3000.00"));
        assertThat(ethResponse.getAskPrice()).isEqualTo(new BigDecimal("3050.00"));
        assertThat(ethResponse.getBidSource()).isEqualTo("HUOBI");
        assertThat(ethResponse.getAskSource()).isEqualTo("BINANCE");
    }

    @Test
    void getLatestBestPrices_WhenNoPrices_ShouldReturnEmptyList() {
        // Given
        when(cryptoPriceMapper.findLatestPrices()).thenReturn(Arrays.asList());

        // When
        List<BestPriceResponse> result = priceService.getLatestBestPrices();

        // Then
        assertThat(result).isEmpty();
    }
}
