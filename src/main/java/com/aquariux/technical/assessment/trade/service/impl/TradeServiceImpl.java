package com.aquariux.technical.assessment.trade.service.impl;

import com.aquariux.technical.assessment.trade.dto.request.TradeRequest;
import com.aquariux.technical.assessment.trade.dto.response.TradeResponse;
import com.aquariux.technical.assessment.trade.entity.*;
import com.aquariux.technical.assessment.trade.enums.TradeType;
import com.aquariux.technical.assessment.trade.exception.TradeException;
import com.aquariux.technical.assessment.trade.mapper.*;
import com.aquariux.technical.assessment.trade.service.*;

import jakarta.validation.Validator;

import lombok.RequiredArgsConstructor;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.time.*;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.stream.Stream;

@Service
@RequiredArgsConstructor
public class TradeServiceImpl implements TradeServiceInterface {
    private final TradeMapper trades;
    private final UserMapper users;
    private final UserWalletMapper wallets;
    private final CryptoPairMapper pairs;
    private final CryptoPriceMapper prices;
    private final MonetaryPolicy money;
    private final QuotePolicy quotes;
    private final Validator validator;
    private final Clock clock;

    // A01/A04: Internal full-fill settlement only; no external exchange side effects in this
    // transaction.
    // D04: The database lock coordinates multiple API processes. Any exception rolls back all
    // writes.
    @Override
    @Transactional(isolation = Isolation.READ_COMMITTED, rollbackFor = Exception.class, timeout = 2)
    public TradeResponse executeTrade(TradeRequest request, String key) {
        if (request == null || !validator.validate(request).isEmpty())
            fail(HttpStatus.BAD_REQUEST, "INVALID_REQUEST", "Invalid trade request");
        if (key == null || !key.matches("[!-~]{1,128}"))
            fail(
                    HttpStatus.BAD_REQUEST,
                    "INVALID_IDEMPOTENCY_KEY",
                    "A printable 1-128 character Idempotency-Key is required");
        // ASSUMPTION: Requests are pre-authenticated, as specified by the assessment.
        BigDecimal quantity = money.quantity(request.getQuantity());
        String fingerprint = fingerprint(request, quantity);
        if (users.lockUser(request.getUserId()) == null)
            fail(HttpStatus.NOT_FOUND, "USER_NOT_FOUND", "User does not exist");

        // A08/D06: Check replay before live prices/eligibility. Failed transactions leave no key
        // reservation.
        Trade replay = trades.findByKey(request.getUserId(), key);
        if (replay != null) {
            if (!fingerprint.equals(replay.getRequestFingerprint()))
                fail(
                        HttpStatus.CONFLICT,
                        "IDEMPOTENCY_CONFLICT",
                        "This key was already used for a different request");
            return response(replay);
        }
        CryptoPair pair = pairs.findByPairName(request.getPairName());
        if (pair == null)
            fail(HttpStatus.NOT_FOUND, "PAIR_NOT_FOUND", "Trading pair does not exist");
        if (!Boolean.TRUE.equals(pair.getActive()))
            fail(HttpStatus.CONFLICT, "PAIR_INACTIVE", "Trading pair is inactive");
        for (Long symbolId :
                Stream.of(pair.getBaseSymbolId(), pair.getQuoteSymbolId()).sorted().toList()) {
            if (!Boolean.TRUE.equals(pairs.isSymbolActive(symbolId)))
                fail(HttpStatus.CONFLICT, "PAIR_INACTIVE", "Trading symbol is inactive");
        }
        // ASSUMPTION: Catalog administration is outside this assessment; pair/symbol status is
        // validated at execution. Per-user locks serialize settlement without locking a whole pair.
        var holdings = wallets.lockWallets(request.getUserId());
        boolean buy = request.getTradeType() == TradeType.BUY;
        Long debitId = buy ? pair.getQuoteSymbolId() : pair.getBaseSymbolId();
        Long creditId = buy ? pair.getBaseSymbolId() : pair.getQuoteSymbolId();
        UserWallet debit = find(holdings, debitId);
        UserWallet credit = find(holdings, creditId);
        if (debit == null)
            fail(HttpStatus.CONFLICT, "INSUFFICIENT_BALANCE", "Insufficient available balance");

        CryptoPrice snapshot = prices.findExecutionPrice(pair.getId());
        // REASON: Measure freshness after waiting for every lock; never lock in a quote while
        // queued.
        Instant now = clock.instant().truncatedTo(ChronoUnit.MICROS);
        quotes.validate(snapshot, request.getTradeType(), now);
        BigDecimal price = buy ? snapshot.getAskPrice() : snapshot.getBidPrice();
        BigDecimal total = money.notional(quantity, price);
        BigDecimal debitAmount = buy ? total : quantity;
        BigDecimal creditAmount = buy ? quantity : total;
        BigDecimal creditBefore =
                credit == null ? BigDecimal.ZERO.setScale(8) : credit.getBalance();
        if (debit.getBalance().compareTo(debitAmount) < 0)
            fail(HttpStatus.CONFLICT, "INSUFFICIENT_BALANCE", "Insufficient available balance");
        if (creditBefore.add(creditAmount).compareTo(MonetaryPolicy.MAX) > 0)
            fail(
                    HttpStatus.CONFLICT,
                    "BALANCE_LIMIT_EXCEEDED",
                    "Resulting wallet balance is outside supported limits");
        LocalDateTime time = LocalDateTime.ofInstant(now, ZoneOffset.UTC);
        requireOne(wallets.debit(request.getUserId(), debitId, debitAmount, time));
        // A12: Only the positive credited wallet is created, and creation rolls back with the
        // trade.
        requireOne(
                credit == null
                        ? wallets.create(request.getUserId(), creditId, creditAmount, time)
                        : wallets.credit(request.getUserId(), creditId, creditAmount, time));
        Trade trade = new Trade();
        trade.setUserId(request.getUserId());
        trade.setCryptoPairId(pair.getId());
        trade.setPairName(pair.getPairName());
        trade.setTradeType(request.getTradeType().name());
        trade.setQuantity(quantity);
        trade.setPrice(price);
        trade.setTotalAmount(total);
        trade.setTradeTime(time);
        trade.setIdempotencyKey(key);
        trade.setRequestFingerprint(fingerprint);
        trade.setPriceSnapshotId(snapshot.getId());
        trade.setPriceSource(buy ? snapshot.getAskSource() : snapshot.getBidSource());
        LocalDateTime event = buy ? snapshot.getAskProviderAt() : snapshot.getBidProviderAt();
        trade.setPriceObservedAt(
                event != null
                        ? event
                        : (buy ? snapshot.getAskReceivedAt() : snapshot.getBidReceivedAt()));
        requireOne(trades.insert(trade));
        return response(trade);
    }

    private static UserWallet find(List<UserWallet> values, Long symbolId) {
        return values.stream()
                .filter(w -> w.getSymbolId().equals(symbolId))
                .findFirst()
                .orElse(null);
    }

    private static void requireOne(int affected) {
        if (affected != 1)
            throw new IllegalStateException("Settlement invariant: expected one affected row");
    }

    private static String fingerprint(TradeRequest request, BigDecimal quantity) {
        String canonical =
                "v1|"
                        + request.getUserId()
                        + "|"
                        + request.getPairName()
                        + "|"
                        + request.getTradeType()
                        + "|"
                        + quantity.toPlainString();
        try {
            return HexFormat.of()
                    .formatHex(
                            MessageDigest.getInstance("SHA-256")
                                    .digest(canonical.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException(ex);
        }
    }

    private static TradeResponse response(Trade t) {
        return new TradeResponse(
                t.getId(),
                t.getUserId(),
                t.getPairName(),
                t.getTradeType(),
                t.getQuantity(),
                t.getPrice(),
                t.getTotalAmount(),
                t.getPriceSource(),
                t.getPriceSnapshotId(),
                t.getPriceObservedAt().toInstant(ZoneOffset.UTC),
                t.getTradeTime().toInstant(ZoneOffset.UTC));
    }

    private static void fail(HttpStatus status, String code, String detail) {
        throw new TradeException(status, code, detail);
    }
}
