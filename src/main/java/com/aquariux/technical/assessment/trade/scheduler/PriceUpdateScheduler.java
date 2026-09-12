package com.aquariux.technical.assessment.trade.scheduler;

import com.aquariux.technical.assessment.trade.pricing.*;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.concurrent.*;

@Component
@ConditionalOnProperty(
        name = "trading.pricing.enabled",
        havingValue = "true",
        matchIfMissing = true)
@RequiredArgsConstructor
@Slf4j
public class PriceUpdateScheduler {
    private final MarketDataClient client;
    private final PricePublisher publisher;
    private final ExecutorService priceExecutor;

    @Scheduled(fixedDelayString = "${trading.pricing.poll-ms:250}")
    public void updatePrices() {
        try {
            // REASON: Both sources run independently outside every database transaction.
            List<Callable<List<MarketQuote>>> tasks =
                    List.of(() -> client.fetch("BINANCE"), () -> client.fetch("HUOBI"));
            // REASON: A wall-clock deadline and bounded executor prevent slow/trickling responses
            // from occupying the scheduler forever or accumulating unbounded queued requests.
            var results = priceExecutor.invokeAll(tasks, 600, TimeUnit.MILLISECONDS);
            var quotes = new ArrayList<MarketQuote>();
            for (var result : results) {
                if (!result.isCancelled()) quotes.addAll(result.get());
            }
            publisher.publish(quotes);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
        } catch (Exception ex) {
            // REASON: The next scheduled run recovers; failed fetches never refresh stored quote
            // timestamps.
            log.warn("Price update failed type={}", ex.getClass().getSimpleName());
        }
    }
}
