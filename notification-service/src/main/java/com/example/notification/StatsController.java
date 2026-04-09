package com.example.notification;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Map;

@RestController
public class StatsController {

    private final NotificationSubscriber subscriber;

    public StatsController(NotificationSubscriber subscriber) {
        this.subscriber = subscriber;
    }

    @GetMapping("/stats")
    public Map<String, Object> getStats() {
        long count = subscriber.getMessageCount();
        long uptime = Duration.between(subscriber.getStartTime(), LocalDateTime.now()).getSeconds();
        double throughput = uptime > 0 ? (double) count / uptime : 0;

        return Map.of(
                "service", "notification-service",
                "messagesProcessed", count,
                "avgLatencyMs", subscriber.getAvgLatencyMs(),
                "uptimeSeconds", uptime,
                "throughputPerSecond", Math.round(throughput * 100.0) / 100.0
        );
    }
}
