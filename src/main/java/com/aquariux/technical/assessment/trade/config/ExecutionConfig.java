package com.aquariux.technical.assessment.trade.config;

import com.fasterxml.jackson.core.StreamReadConstraints;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.module.SimpleModule;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;

import org.springframework.boot.autoconfigure.jackson.Jackson2ObjectMapperBuilderCustomizer;
import org.springframework.context.annotation.*;

import java.math.BigDecimal;
import java.time.Clock;

@Configuration
public class ExecutionConfig {
    @Bean
    Clock clock() {
        return Clock.systemUTC();
    }

    @Bean
    Jackson2ObjectMapperBuilderCustomizer exactJson() {
        // D01: Decimal strings prevent client floating-point loss; reject unexpected price/total
        // fields.
        return builder -> {
            var module = new SimpleModule();
            module.addSerializer(BigDecimal.class, ToStringSerializer.instance);
            builder.modulesToInstall(module);
            builder.featuresToEnable(
                    DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES,
                    DeserializationFeature.FAIL_ON_NUMBERS_FOR_ENUMS);
            builder.featuresToDisable(DeserializationFeature.ACCEPT_FLOAT_AS_INT);
            builder.postConfigurer(
                    mapper ->
                            mapper.getFactory()
                                    .setStreamReadConstraints(
                                            StreamReadConstraints.builder()
                                                    .maxNumberLength(64)
                                                    .maxStringLength(1024)
                                                    .maxNestingDepth(10)
                                                    .build()));
        };
    }
}
