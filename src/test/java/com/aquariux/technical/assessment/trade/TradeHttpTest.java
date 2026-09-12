package com.aquariux.technical.assessment.trade;

import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.time.*;

@SpringBootTest(properties = "spring.datasource.url=jdbc:h2:mem:http-tests;DB_CLOSE_DELAY=-1")
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(TradeExecutionTest.TestClock.class)
class TradeHttpTest {
    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;

    @BeforeEach
    void quote() {

        jdbc.update("DELETE FROM trades WHERE idempotency_key IS NOT NULL");
        jdbc.update("DELETE FROM user_wallets WHERE user_id=1 AND symbol_id<>3");
        jdbc.update("UPDATE user_wallets SET balance=1000 WHERE user_id=1");
        var time = LocalDateTime.ofInstant(TradeExecutionTest.NOW, ZoneOffset.UTC);
        jdbc.update(
                """
                INSERT INTO crypto_prices(crypto_pair_id,bid_price,ask_price,bid_source,ask_source,created_at,
                bid_received_at,ask_received_at,eligible) VALUES(1,99,100,'BINANCE','HUOBI',?,?,?,TRUE)
                """,
                time,
                time,
                time);
    }

    String valid =
            "{\"userId\":1,\"pairName\":\"BTCUSDT\",\"tradeType\":\"BUY\",\"quantity\":\"1\"}";

    @Test
    void returnsPersistedDecimalsAndIdenticalReplay() throws Exception {
        var first =
                mvc.perform(
                                post("/api/trades/execute")
                                        .contentType("application/json")
                                        .header("Idempotency-Key", "http")
                                        .content(valid))
                        .andExpect(status().isOk())
                        .andExpect(jsonPath("$.price").value("100.00000000"))
                        .andExpect(jsonPath("$.quantity").value("1.00000000"))
                        .andExpect(jsonPath("$.priceSource").value("HUOBI"))
                        .andReturn()
                        .getResponse()
                        .getContentAsString();
        var replay =
                mvc.perform(
                                post("/api/trades/execute")
                                        .contentType("application/json")
                                        .header("Idempotency-Key", "http")
                                        .content(valid))
                        .andExpect(status().isOk())
                        .andReturn()
                        .getResponse()
                        .getContentAsString();
        assertThat(replay).isEqualTo(first);
    }

    @Test
    void rejectsBadInputsWithStructuredErrorsAndNoMutation() throws Exception {
        for (String body :
                new String[] {
                    "{}",
                    valid.replace("\"BUY\"", "0"),
                    valid.replace("\"BUY\"", "\"HOLD\""),
                    valid.replace("\"1\"", "\"0\""),
                    valid.replace("\"1\"", "\"0.000000001\""),
                    valid.replace("BTCUSDT", "DOGEUSDT"),
                    valid.replace("\"userId\":1", "\"userId\":1.5"),
                    valid.replace("}", ",\"price\":1}"),
                    "{invalid"
                }) {
            mvc.perform(
                            post("/api/trades/execute")
                                    .contentType("application/json")
                                    .header("Idempotency-Key", "bad")
                                    .content(body))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
        }
        assertThat(
                        jdbc.queryForObject(
                                "SELECT COUNT(*) FROM trades WHERE idempotency_key IS NOT NULL",
                                Integer.class))
                .isZero();
    }

    @Test
    void requiresIdempotencyHeaderAndJson() throws Exception {
        mvc.perform(post("/api/trades/execute").contentType("application/json").content(valid))
                .andExpect(status().isBadRequest());
        mvc.perform(
                        post("/api/trades/execute")
                                .contentType("text/plain")
                                .header("Idempotency-Key", "a")
                                .content(valid))
                .andExpect(status().isUnsupportedMediaType());
    }

    @Test
    void walletReadIncludesUnacquiredAssetsWithoutCreatingRows() throws Exception {
        mvc.perform(get("/api/wallets/user/1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(3))
                .andExpect(jsonPath("$[0].symbol").value("BTC"));
        assertThat(
                        jdbc.queryForObject(
                                "SELECT COUNT(*) FROM user_wallets WHERE user_id=1", Integer.class))
                .isEqualTo(1);
    }
}
