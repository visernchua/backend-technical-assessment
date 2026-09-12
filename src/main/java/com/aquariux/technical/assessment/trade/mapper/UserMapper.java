package com.aquariux.technical.assessment.trade.mapper;

import org.apache.ibatis.annotations.*;

@Mapper
public interface UserMapper {
    // A07: Every wallet writer must acquire this parent lock, including external funding writers.
    @Select("SELECT id FROM users WHERE id = #{userId} FOR UPDATE")
    Long lockUser(Long userId);
}
