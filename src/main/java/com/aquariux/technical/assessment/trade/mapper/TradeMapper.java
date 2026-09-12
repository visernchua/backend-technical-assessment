package com.aquariux.technical.assessment.trade.mapper;

import com.aquariux.technical.assessment.trade.entity.Trade;

import org.apache.ibatis.annotations.*;

@Mapper
public interface TradeMapper {
    @Select(
            """
            SELECT t.*, p.pair_name FROM trades t JOIN crypto_pairs p ON p.id = t.crypto_pair_id
            WHERE t.user_id = #{userId} AND t.idempotency_key = #{key}
            """)
    Trade findByKey(@Param("userId") Long userId, @Param("key") String key);

    @Insert(
            """
            INSERT INTO trades (user_id,crypto_pair_id,trade_type,quantity,price,total_amount,trade_time,
              idempotency_key,request_fingerprint,price_snapshot_id,price_source,price_observed_at)
            VALUES (#{userId},#{cryptoPairId},#{tradeType},#{quantity},#{price},#{totalAmount},#{tradeTime},
              #{idempotencyKey},#{requestFingerprint},#{priceSnapshotId},#{priceSource},#{priceObservedAt})
            """)
    @Options(useGeneratedKeys = true, keyProperty = "id")
    int insert(Trade trade);
}
