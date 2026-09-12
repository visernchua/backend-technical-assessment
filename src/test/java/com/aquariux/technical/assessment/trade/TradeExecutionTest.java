package com.aquariux.technical.assessment.trade;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.aquariux.technical.assessment.trade.dto.request.TradeRequest;
import com.aquariux.technical.assessment.trade.entity.CryptoPrice;
import com.aquariux.technical.assessment.trade.enums.TradeType;
import com.aquariux.technical.assessment.trade.exception.TradeException;
import com.aquariux.technical.assessment.trade.mapper.*;
import com.aquariux.technical.assessment.trade.service.TradeServiceInterface;

import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.*;
import org.springframework.context.annotation.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

import java.math.BigDecimal;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.IntFunction;

@SpringBootTest
@ActiveProfiles("test")
@Import(TradeExecutionTest.TestClock.class)
class TradeExecutionTest {
    static final Instant NOW = Instant.parse("2026-09-12T08:00:00Z");
    static final AtomicLong USER_IDS = new AtomicLong(1000);

    @TestConfiguration
    static class TestClock {
        @Bean
        @Primary
        Clock testClock() {
            return Clock.fixed(NOW, ZoneOffset.UTC);
        }
    }

    @Autowired JdbcTemplate jdbc;
    @Autowired TradeServiceInterface service;
    @Autowired CryptoPriceMapper priceMapper;
    @MockitoSpyBean UserWalletMapper walletMapper;
    @MockitoSpyBean TradeMapper tradeMapper;
    long user;

    @BeforeEach
    void fixture() {
        user = newUser("1000");
        jdbc.update("UPDATE crypto_pairs SET active=TRUE");
        jdbc.update("UPDATE symbols SET active=TRUE");
        price(1, "99", "100", NOW.minusMillis(10), true);
        price(2, "9", "10", NOW.minusMillis(10), true);
    }

    long newUser(String balance) {
        long id = USER_IDS.incrementAndGet();
        jdbc.update(
                "INSERT INTO users(id,username,email,password) VALUES(?,?,?,?)",
                id,
                "user" + id,
                id + "@test.invalid",
                "unused");
        jdbc.update(
                "INSERT INTO user_wallets(user_id,symbol_id,balance) VALUES(?,3,?)",
                id,
                new BigDecimal(balance));
        return id;
    }

    CryptoPrice price(long pair, String bid, String ask, Instant observed, boolean eligible) {
        CryptoPrice p = new CryptoPrice();
        p.setCryptoPairId(pair);
        p.setBidPrice(new BigDecimal(bid));
        p.setAskPrice(new BigDecimal(ask));
        p.setBidSource("BINANCE");
        p.setAskSource("HUOBI");
        p.setCreatedAt(LocalDateTime.ofInstant(NOW, ZoneOffset.UTC));
        p.setBidReceivedAt(LocalDateTime.ofInstant(observed, ZoneOffset.UTC));
        p.setAskReceivedAt(p.getBidReceivedAt());
        p.setEligible(eligible);
        priceMapper.insertPrice(p);
        return p;
    }

    TradeRequest request(long id, String pair, String side, String quantity) {
        TradeRequest r = new TradeRequest();
        r.setUserId(id);
        r.setPairName(pair);
        r.setTradeType(TradeType.valueOf(side));
        r.setQuantity(new BigDecimal(quantity));
        return r;
    }

    BigDecimal balance(long id, long symbol) {
        return jdbc.queryForObject(
                "SELECT COALESCE(SUM(balance),0) FROM user_wallets WHERE user_id=? AND symbol_id=?",
                BigDecimal.class,
                id,
                symbol);
    }

    int count(String table, long id) {
        return jdbc.queryForObject(
                "SELECT COUNT(*) FROM " + table + " WHERE user_id=?", Integer.class, id);
    }

    void error(Runnable action, String code) {
        assertThatThrownBy(action::run)
                .isInstanceOfSatisfying(
                        TradeException.class, e -> assertThat(e.getCode()).isEqualTo(code));
    }

    @ParameterizedTest
    @ValueSource(strings = {"BTCUSDT", "ETHUSDT"})
    void buysAndSellsUsingTheCorrectSide(String pair) {
        long base = pair.equals("BTCUSDT") ? 1 : 2;
        var buy = service.executeTrade(request(user, pair, "BUY", "1"), "buy");
        assertThat(buy.priceSource()).isEqualTo("HUOBI");
        assertThat(buy.price()).isEqualByComparingTo(base == 1 ? "100" : "10");
        assertThat(balance(user, base)).isEqualByComparingTo("1");
        var sell = service.executeTrade(request(user, pair, "SELL", "1"), "sell");
        assertThat(sell.priceSource()).isEqualTo("BINANCE");
        assertThat(balance(user, base)).isZero();
        assertThat(balance(user, 3)).isEqualByComparingTo("999");
        assertThat(count("trades", user)).isEqualTo(2);
    }

    @Test
    void spendsExactBalanceAndRetainsZeroWallet() {
        service.executeTrade(request(user, "BTCUSDT", "BUY", "10"), "all");
        assertThat(balance(user, 3)).isZero();
        assertThat(balance(user, 1)).isEqualByComparingTo("10");
        assertThat(count("user_wallets", user)).isEqualTo(2);
    }

    @Test
    void insufficientFundsAndMissingDebitDoNotCreateWallets() {
        error(
                () -> service.executeTrade(request(user, "BTCUSDT", "BUY", "11"), "too-much"),
                "INSUFFICIENT_BALANCE");
        error(
                () -> service.executeTrade(request(user, "BTCUSDT", "SELL", "1"), "missing"),
                "INSUFFICIENT_BALANCE");
        assertThat(count("trades", user)).isZero();
        assertThat(count("user_wallets", user)).isEqualTo(1);
    }

    @Test
    void replaysBeforeCheckingChangedEligibilityOrPrice() {
        var first = service.executeTrade(request(user, "BTCUSDT", "BUY", "1.00"), "key");
        jdbc.update("UPDATE crypto_pairs SET active=FALSE WHERE id=1");
        price(1, "99", "100", NOW.minusSeconds(10), true);
        assertThat(service.executeTrade(request(user, "BTCUSDT", "BUY", "1.00000000"), "key"))
                .isEqualTo(first);
        error(
                () -> service.executeTrade(request(user, "BTCUSDT", "BUY", "2"), "key"),
                "IDEMPOTENCY_CONFLICT");
        assertThat(count("trades", user)).isEqualTo(1);
    }

    @Test
    void failedRequestsDoNotReserveKeys() {
        error(
                () -> service.executeTrade(request(user, "BTCUSDT", "BUY", "11"), "retry"),
                "INSUFFICIENT_BALANCE");
        jdbc.update("UPDATE user_wallets SET balance=2000 WHERE user_id=?", user);
        service.executeTrade(request(user, "BTCUSDT", "BUY", "11"), "retry");
        assertThat(count("trades", user)).isEqualTo(1);
    }

    @Test
    void sameKeyCanBeUsedByDifferentUsers() {
        long other = newUser("1000");
        service.executeTrade(request(user, "BTCUSDT", "BUY", "1"), "same");
        service.executeTrade(request(other, "BTCUSDT", "BUY", "1"), "same");
        assertThat(count("trades", other)).isEqualTo(1);
    }

    @Test
    void staleFutureCrossedAndBoundaryQuotesFailClosed() {
        price(1, "99", "100", NOW.minusMillis(1000), true);
        error(
                () -> service.executeTrade(request(user, "BTCUSDT", "BUY", "1"), "stale"),
                "PRICE_STALE");
        price(1, "99", "100", NOW.plusMillis(1), true);
        error(
                () -> service.executeTrade(request(user, "BTCUSDT", "BUY", "1"), "future"),
                "PRICE_STALE");
        price(1, "101", "100", NOW, true == false);
        error(
                () -> service.executeTrade(request(user, "BTCUSDT", "BUY", "1"), "crossed"),
                "PRICE_INVALID");
        price(1, "99", "100", NOW.minusMillis(999), true);
        service.executeTrade(request(user, "BTCUSDT", "BUY", "1"), "boundary");
        assertThat(count("trades", user)).isEqualTo(1);
    }

    @Test
    void inactiveSymbolAndUnknownUserFailWithoutMutation() {
        jdbc.update("UPDATE symbols SET active=FALSE WHERE id=1");
        error(
                () -> service.executeTrade(request(user, "BTCUSDT", "BUY", "1"), "inactive"),
                "PAIR_INACTIVE");
        error(
                () -> service.executeTrade(request(99999999, "BTCUSDT", "BUY", "1"), "unknown"),
                "USER_NOT_FOUND");
        assertThat(count("trades", user)).isZero();
    }

    @Test
    void creditOverflowRollsBack() {
        jdbc.update(
                "INSERT INTO user_wallets(user_id,symbol_id,balance)"
                        + " VALUES(?,1,999999999999.99999999)",
                user);
        error(
                () -> service.executeTrade(request(user, "BTCUSDT", "BUY", "1"), "overflow"),
                "BALANCE_LIMIT_EXCEEDED");
        assertThat(balance(user, 3)).isEqualByComparingTo("1000");
        assertThat(count("trades", user)).isZero();
    }

    @ParameterizedTest
    @ValueSource(strings = {"credit", "trade"})
    void databaseFailuresRollbackAllWrites(String stage) {
        if (stage.equals("credit"))
            doThrow(new IllegalStateException("injected"))
                    .when(walletMapper)
                    .create(anyLong(), anyLong(), any(), any());
        if (stage.equals("trade"))
            doThrow(new IllegalStateException("injected")).when(tradeMapper).insert(any());
        assertThatThrownBy(
                        () -> service.executeTrade(request(user, "BTCUSDT", "BUY", "1"), "failure"))
                .isInstanceOf(IllegalStateException.class);
        // REASON: This test has no ambient transaction; these reads observe the completed service
        // rollback.
        assertThat(balance(user, 3)).isEqualByComparingTo("1000");
        assertThat(count("user_wallets", user)).isEqualTo(1);
        assertThat(count("trades", user)).isZero();
    }

    @Test
    void supportsOneHundredConcurrentSameUserRequestsWithoutOverspending() throws Exception {
        List<String> outcomes =
                parallel(
                        100,
                        i -> {
                            try {
                                service.executeTrade(
                                        request(user, "BTCUSDT", "BUY", "1"), "concurrent-" + i);
                                return "SUCCESS";
                            } catch (TradeException ex) {
                                return ex.getCode();
                            }
                        });
        assertThat(outcomes.stream().filter("SUCCESS"::equals).count()).isEqualTo(10);
        assertThat(outcomes.stream().filter("INSUFFICIENT_BALANCE"::equals).count()).isEqualTo(90);
        assertThat(balance(user, 3)).isZero();
        assertThat(balance(user, 1)).isEqualByComparingTo("10");
        assertThat(count("trades", user)).isEqualTo(10);
    }

    @Test
    void concurrentReplayCreatesExactlyOneTradeAndOneDestinationWallet() throws Exception {
        var ids =
                parallel(
                        100,
                        i ->
                                service.executeTrade(request(user, "BTCUSDT", "BUY", "1"), "shared")
                                        .tradeId());
        assertThat(new HashSet<>(ids)).hasSize(1);
        assertThat(count("trades", user)).isEqualTo(1);
        assertThat(count("user_wallets", user)).isEqualTo(2);
        assertThat(balance(user, 3)).isEqualByComparingTo("900");
    }

    @Test
    void concurrentDifferentPairsCannotLoseUpdatesToSharedUsdt() throws Exception {
        parallel(
                20,
                i ->
                        service.executeTrade(
                                request(user, i % 2 == 0 ? "BTCUSDT" : "ETHUSDT", "BUY", "0.1"),
                                "pair-" + i));
        assertThat(balance(user, 3)).isEqualByComparingTo("890");
        assertThat(balance(user, 1)).isEqualByComparingTo("1");
        assertThat(balance(user, 2)).isEqualByComparingTo("1");
    }

    @Test
    void concurrentBuysAndSellsPreserveBothBalances() throws Exception {
        jdbc.update("UPDATE user_wallets SET balance=10000 WHERE user_id=? AND symbol_id=3", user);
        jdbc.update("INSERT INTO user_wallets(user_id,symbol_id,balance) VALUES(?,1,100)", user);
        parallel(
                100,
                i ->
                        service.executeTrade(
                                request(user, "BTCUSDT", i % 2 == 0 ? "BUY" : "SELL", "1"),
                                "mixed-" + i));
        assertThat(balance(user, 1)).isEqualByComparingTo("100");
        assertThat(balance(user, 3)).isEqualByComparingTo("9950");
        assertThat(count("trades", user)).isEqualTo(100);
    }

    @Test
    void hundredIndependentUsersCanTrade() throws Exception {
        var ids = new ArrayList<Long>();
        for (int i = 0; i < 100; i++) ids.add(newUser("1000"));
        var results =
                parallel(
                        100,
                        i ->
                                service.executeTrade(
                                        request(ids.get(i), "BTCUSDT", "BUY", "1"), "independent"));
        assertThat(results).hasSize(100);
        for (long id : ids) {
            assertThat(balance(id, 3)).isEqualByComparingTo("900");
            assertThat(count("trades", id)).isEqualTo(1);
        }
    }

    static <T> List<T> parallel(int count, IntFunction<T> action) throws Exception {
        try (var executor = Executors.newFixedThreadPool(count)) {
            var ready = new CountDownLatch(count);
            var go = new CountDownLatch(1);
            var futures = new ArrayList<Future<T>>();
            for (int i = 0; i < count; i++) {
                int index = i;
                futures.add(
                        executor.submit(
                                () -> {
                                    ready.countDown();
                                    go.await();
                                    return action.apply(index);
                                }));
            }
            assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
            go.countDown();
            var result = new ArrayList<T>();
            for (var future : futures) result.add(future.get(30, TimeUnit.SECONDS));
            return result;
        }
    }
}
