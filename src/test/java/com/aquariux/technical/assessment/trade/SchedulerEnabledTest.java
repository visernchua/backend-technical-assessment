package com.aquariux.technical.assessment.trade;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.aquariux.technical.assessment.trade.pricing.*;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

@SpringBootTest(
        properties = {
            "spring.datasource.url=jdbc:h2:mem:scheduler-tests;DB_CLOSE_DELAY=-1",
            "trading.pricing.enabled=true",
            "trading.pricing.poll-ms=50"
        })
@ActiveProfiles("test")
class SchedulerEnabledTest {
    @MockitoBean MarketDataClient client;
    @MockitoBean PricePublisher publisher;

    @Test
    void schedulingIsActuallyEnabled() {
        // REASON: This tests Spring scheduling activation, not a direct manual method call.
        verify(publisher, timeout(2000).atLeastOnce()).publish(anyList());
    }
}
