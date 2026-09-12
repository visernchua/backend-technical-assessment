package com.aquariux.technical.assessment.trade.mapper;

import com.aquariux.technical.assessment.trade.dto.internal.UserWalletDto;
import com.aquariux.technical.assessment.trade.entity.UserWallet;

import org.apache.ibatis.annotations.*;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

@Mapper
public interface UserWalletMapper {
    // A12: Represent unacquired assets as zero in reads without creating database rows.
    @Select(
            """
            SELECT s.symbol, s.name, COALESCE(uw.balance, 0) AS balance
            FROM symbols s LEFT JOIN user_wallets uw ON s.id = uw.symbol_id AND uw.user_id = #{userId}
            WHERE s.symbol IN ('BTC','ETH','USDT') AND EXISTS (SELECT 1 FROM users WHERE id = #{userId})
            ORDER BY s.symbol
            """)
    List<UserWalletDto> findByUserId(Long userId);

    @Select("SELECT * FROM user_wallets WHERE user_id = #{userId} ORDER BY symbol_id FOR UPDATE")
    List<UserWallet> lockWallets(Long userId);

    @Update(
            """
            UPDATE user_wallets SET balance = balance - #{amount}, updated_at = #{time}
            WHERE user_id = #{userId} AND symbol_id = #{symbolId} AND balance >= #{amount}
            """)
    int debit(
            @Param("userId") Long userId,
            @Param("symbolId") Long symbolId,
            @Param("amount") BigDecimal amount,
            @Param("time") LocalDateTime time);

    @Update(
            """
            UPDATE user_wallets SET balance = balance + #{amount}, updated_at = #{time}
            WHERE user_id = #{userId} AND symbol_id = #{symbolId} AND balance <= 999999999999.99999999 - #{amount}
            """)
    int credit(
            @Param("userId") Long userId,
            @Param("symbolId") Long symbolId,
            @Param("amount") BigDecimal amount,
            @Param("time") LocalDateTime time);

    @Insert(
            "INSERT INTO user_wallets (user_id,symbol_id,balance,updated_at) VALUES"
                    + " (#{userId},#{symbolId},#{amount},#{time})")
    int create(
            @Param("userId") Long userId,
            @Param("symbolId") Long symbolId,
            @Param("amount") BigDecimal amount,
            @Param("time") LocalDateTime time);
}
