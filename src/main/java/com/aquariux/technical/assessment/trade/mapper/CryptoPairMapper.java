package com.aquariux.technical.assessment.trade.mapper;

import com.aquariux.technical.assessment.trade.entity.CryptoPair;

import org.apache.ibatis.annotations.*;

@Mapper
public interface CryptoPairMapper {
    @Select("SELECT id FROM crypto_pairs WHERE pair_name = #{pairName}")
    Long findIdByPairName(String pairName);

    @Select("SELECT * FROM crypto_pairs WHERE pair_name = #{pairName}")
    CryptoPair findByPairName(String pairName);

    @Select("SELECT active FROM symbols WHERE id = #{id}")
    Boolean isSymbolActive(Long id);
}
