package com.aquariux.technical.assessment.trade;

import static org.assertj.core.api.Assertions.*;

import com.aquariux.technical.assessment.trade.mapper.CryptoPriceMapper;
import com.aquariux.technical.assessment.trade.pricing.*;

import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.math.BigDecimal;
import java.time.*;
import java.util.*;

@SpringBootTest(properties = "spring.datasource.url=jdbc:h2:mem:publisher-tests;DB_CLOSE_DELAY=-1")
@ActiveProfiles("test")
@Import(TradeExecutionTest.TestClock.class)
class PricePublisherTest {
    @Autowired PricePublisher publisher;
    @Autowired CryptoPriceMapper prices;
    @Autowired JdbcTemplate jdbc;
    LocalDateTime now = LocalDateTime.ofInstant(TradeExecutionTest.NOW, ZoneOffset.UTC);

    @BeforeEach
    void clean() {
        jdbc.update("DELETE FROM crypto_prices");
    }

    MarketQuote q(String pair, String bid, String ask, String source) {
        return new MarketQuote(
                pair,
                new BigDecimal(bid),
                new BigDecimal(ask),
                source,
                now.minusNanos(10_000_000),
                null);
    }

    @Test
    void preservesPairSideAndSource() {
        assertThat(
                        publisher.publish(
                                List.of(
                                        q("BTCUSDT", "100", "103", "BINANCE"),
                                        q("BTCUSDT", "101", "102", "HUOBI"),
                                        q("ETHUSDT", "10", "13", "HUOBI"),
                                        q("ETHUSDT", "11", "12", "BINANCE"))))
                .isEqualTo(2);
        var btc = prices.findExecutionPrice(1L);
        var eth = prices.findExecutionPrice(2L);
        assertThat(btc.getBidPrice()).isEqualByComparingTo("101");
        assertThat(btc.getAskPrice()).isEqualByComparingTo("102");
        assertThat(btc.getBidSource()).isEqualTo("HUOBI");
        assertThat(btc.getAskSource()).isEqualTo("HUOBI");
        assertThat(eth.getBidPrice()).isEqualByComparingTo("11");
        assertThat(eth.getAskPrice()).isEqualByComparingTo("12");
        assertThat(eth.getAskSource()).isEqualTo("BINANCE");
        assertThat(prices.findLatestPrices()).hasSize(2);
    }

    @Test
    void crossedAggregateIsRecordedButNotExecutable() {
        publisher.publish(
                List.of(
                        q("BTCUSDT", "100", "101", "BINANCE"),
                        q("BTCUSDT", "102", "103", "HUOBI")));
        assertThat(prices.findExecutionPrice(1L).getEligible()).isFalse();
    }

    @Test
    void oldOrMissingQuotesNeverRefreshHistory() {
        publisher.publish(List.of(q("BTCUSDT", "100", "101", "BINANCE")));
        long id = prices.findExecutionPrice(1L).getId();
        publisher.publish(
                List.of(
                        new MarketQuote(
                                "BTCUSDT",
                                new BigDecimal("100"),
                                new BigDecimal("101"),
                                "BINANCE",
                                now.minusSeconds(2),
                                null)));
        publisher.publish(List.of());
        assertThat(prices.findExecutionPrice(1L).getId()).isEqualTo(id);
    }

    @Test
    void timestampTiesHaveOneDeterministicLatestResult() {
        publisher.publish(List.of(q("BTCUSDT", "100", "101", "BINANCE")));
        publisher.publish(List.of(q("BTCUSDT", "101", "102", "BINANCE")));
        assertThat(prices.findLatestPrices()).hasSize(1);
        assertThat(prices.findLatestPrices().getFirst().getAskPrice()).isEqualByComparingTo("102");
    }
}
