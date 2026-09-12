package com.aquariux.technical.assessment.trade.pricing;

import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.*;
import static org.springframework.test.web.client.response.MockRestResponseCreators.*;

import com.aquariux.technical.assessment.trade.service.MonetaryPolicy;

import org.junit.jupiter.api.*;
import org.springframework.http.MediaType;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestTemplate;

import java.time.*;

class MarketDataClientTest {
    RestTemplate template = new RestTemplate();
    MockRestServiceServer server = MockRestServiceServer.bindTo(template).build();
    MarketDataClient client =
            new MarketDataClient(
                    template,
                    Clock.fixed(Instant.parse("2026-09-12T00:00:00Z"), ZoneOffset.UTC),
                    new MonetaryPolicy());

    @BeforeEach
    void urls() {
        ReflectionTestUtils.setField(client, "binanceUrl", "https://example.test/binance");
        ReflectionTestUtils.setField(client, "huobiUrl", "https://example.test/huobi");
    }

    @Test
    void decimalParsingIsExactAndMalformedPairDoesNotDiscardOtherPairs() {
        server.expect(requestTo("https://example.test/binance"))
                .andRespond(
                        withSuccess(
                                """
                                [{"symbol":"BTCUSDT","bidPrice":"123.12345678","askPrice":"123.12345679","bidQty":"9"},
                                 {"symbol":"ETHUSDT","bidPrice":"not-a-price","askPrice":"1"}]
                                """,
                                MediaType.APPLICATION_JSON));
        var result = client.fetch("BINANCE");
        assertThat(result).hasSize(1);
        assertThat(result.getFirst().bid()).isEqualByComparingTo("123.12345678");
        assertThat(result.getFirst().providerAt()).isNull();
        server.verify();
    }

    @Test
    void huobiUsesProviderResponseTimestampAndCorrectSide() {
        server.expect(requestTo("https://example.test/huobi"))
                .andRespond(
                        withSuccess(
                                """
                                {"status":"ok","ts":1789171200000,"data":[{"symbol":"ethusdt","bid":9.12345678,"ask":10.12345678,"count":4}]}
                                """,
                                MediaType.APPLICATION_JSON));
        var result = client.fetch("HUOBI");
        assertThat(result).hasSize(1);
        assertThat(result.getFirst().pair()).isEqualTo("ETHUSDT");
        assertThat(result.getFirst().bid()).isEqualByComparingTo("9.12345678");
        assertThat(result.getFirst().providerAt()).isNotNull();
        server.verify();
    }

    @Test
    void outageBackoffDoesNotPreventTheOtherSourceFromWorking() {
        server.expect(requestTo("https://example.test/binance")).andRespond(withServerError());
        server.expect(requestTo("https://example.test/huobi"))
                .andRespond(
                        withSuccess(
                                """
                                {"status":"ok","data":[{"symbol":"ethusdt","bid":9,"ask":10}]}
                                """,
                                MediaType.APPLICATION_JSON));
        assertThat(client.fetch("BINANCE")).isEmpty();
        assertThat(client.fetch("BINANCE")).isEmpty();
        assertThat(client.fetch("HUOBI")).hasSize(1);
        server.verify();
    }
}
