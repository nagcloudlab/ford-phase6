package com.example.fraud;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Map;

@RestController
public class StatsController {

    private final FraudDetectionSubscriber subscriber;

    public StatsController(FraudDetectionSubscriber subscriber) {
        this.subscriber = subscriber;
    }

    @GetMapping("/stats")
    public Map<String, Object> getStats() {
        long count = subscriber.getMessageCount();
        long uptime = Duration.between(subscriber.getStartTime(), LocalDateTime.now()).getSeconds();
        double throughput = uptime > 0 ? (double) count / uptime : 0;

        return Map.of(
                "service", "fraud-detection-service",
                "messagesProcessed", count,
                "fraudAlertsRaised", subscriber.getAlertCount(),
                "avgLatencyMs", subscriber.getAvgLatencyMs(),
                "uptimeSeconds", uptime,
                "throughputPerSecond", Math.round(throughput * 100.0) / 100.0
        );
    }
}
