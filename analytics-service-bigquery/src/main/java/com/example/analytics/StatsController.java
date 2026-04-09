package com.example.analytics;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.stream.Collectors;

@RestController
public class StatsController {

    private final AnalyticsSubscriber subscriber;
    private final BigQueryService bigQueryService;

    public StatsController(AnalyticsSubscriber subscriber, BigQueryService bigQueryService) {
        this.subscriber = subscriber;
        this.bigQueryService = bigQueryService;
    }

    @GetMapping("/stats")
    public Map<String, Object> getStats() {
        Map<String, Object> stats = new LinkedHashMap<>();

        long count = subscriber.getMessageCount();
        long uptimeSeconds = Duration.between(subscriber.getStartTime(), Instant.now()).getSeconds();

        stats.put("messagesProcessed", count);
        stats.put("avgLatencyMs", count > 0 ? subscriber.getTotalLatencyMs() / count : 0);
        stats.put("uptimeSeconds", uptimeSeconds);
        stats.put("throughputPerSecond", uptimeSeconds > 0 ? (double) count / uptimeSeconds : 0);
        stats.put("totalTransactionVolume", subscriber.getTotalAmount());
        stats.put("maxTransaction", subscriber.getMaxTransaction());
        stats.put("minTransaction", subscriber.getMinTransaction());
        stats.put("amountDistribution", subscriber.getAmountBuckets().entrySet().stream()
                .collect(Collectors.toMap(Map.Entry::getKey, e -> e.getValue().get())));
        stats.put("topSenders", subscriber.getTopSenders().entrySet().stream()
                .collect(Collectors.toMap(Map.Entry::getKey, e -> e.getValue().get())));
        stats.put("topReceivers", subscriber.getTopReceivers().entrySet().stream()
                .collect(Collectors.toMap(Map.Entry::getKey, e -> e.getValue().get())));

        // BigQuery-specific stats
        Map<String, Object> bqStats = new LinkedHashMap<>();
        bqStats.put("dataset", bigQueryService.getDatasetId());
        bqStats.put("table", bigQueryService.getTableId());
        bqStats.put("rowsInserted", bigQueryService.getRowsInserted());
        bqStats.put("insertErrors", bigQueryService.getInsertErrors());
        stats.put("bigQuery", bqStats);

        return stats;
    }
}
