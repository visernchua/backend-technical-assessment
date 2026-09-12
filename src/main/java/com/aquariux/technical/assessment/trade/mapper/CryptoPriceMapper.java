package com.aquariux.technical.assessment.trade.mapper;

import com.aquariux.technical.assessment.trade.entity.CryptoPrice;

import org.apache.ibatis.annotations.*;

import java.util.List;

@Mapper
public interface CryptoPriceMapper {
    @Insert(
            """
            INSERT INTO crypto_prices (crypto_pair_id,bid_price,ask_price,bid_source,ask_source,created_at,
              bid_received_at,ask_received_at,bid_provider_at,ask_provider_at,eligible)
            VALUES (#{cryptoPairId},#{bidPrice},#{askPrice},#{bidSource},#{askSource},#{createdAt},
              #{bidReceivedAt},#{askReceivedAt},#{bidProviderAt},#{askProviderAt},#{eligible})
            """)
    @Options(useGeneratedKeys = true, keyProperty = "id")
    int insertPrice(CryptoPrice cryptoPrice);

    @Select(
            """
            SELECT cp.*, pair.pair_name FROM crypto_prices cp JOIN crypto_pairs pair ON pair.id = cp.crypto_pair_id
            WHERE cp.id = (SELECT p.id FROM crypto_prices p WHERE p.crypto_pair_id = cp.crypto_pair_id
              ORDER BY p.created_at DESC,p.id DESC LIMIT 1) ORDER BY cp.crypto_pair_id
            """)
    List<CryptoPrice> findLatestPrices();

    // REASON: Do not filter away an invalid newest quote and silently execute against an older one.
    @Select(
            """
            SELECT * FROM crypto_prices WHERE crypto_pair_id = #{pairId} AND bid_received_at IS NOT NULL
            ORDER BY created_at DESC,id DESC LIMIT 1
            """)
    CryptoPrice findExecutionPrice(Long pairId);
}
