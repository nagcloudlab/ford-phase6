package com.example.analytics;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Collectors;

@RestController
public class StatsController {

    private final AnalyticsSubscriber subscriber;

    public StatsController(AnalyticsSubscriber subscriber) {
        this.subscriber = subscriber;
    }

    @GetMapping("/stats")
    public Map<String, Object> getStats() {
        long count = subscriber.getMessageCount();
        long uptime = Duration.between(subscriber.getStartTime(), LocalDateTime.now()).getSeconds();
        double throughput = uptime > 0 ? (double) count / uptime : 0;

        Map<String, Object> stats = new LinkedHashMap<>();
        stats.put("service", "analytics-service");
        stats.put("messagesProcessed", count);
        stats.put("avgLatencyMs", subscriber.getAvgLatencyMs());
        stats.put("uptimeSeconds", uptime);
        stats.put("throughputPerSecond", Math.round(throughput * 100.0) / 100.0);
        stats.put("totalTransactionVolume", subscriber.getTotalAmount());
        stats.put("maxTransaction", subscriber.getMaxTransaction());
        stats.put("minTransaction", subscriber.getMinTransaction());
        stats.put("amountDistribution", toMap(subscriber.getAmountBuckets()));
        stats.put("topSenders", toMap(subscriber.getTopSenders()));
        stats.put("topReceivers", toMap(subscriber.getTopReceivers()));
        return stats;
    }

    private Map<String, Long> toMap(Map<String, AtomicLong> input) {
        return input.entrySet().stream()
                .collect(Collectors.toMap(Map.Entry::getKey, e -> e.getValue().get()));
    }
}
