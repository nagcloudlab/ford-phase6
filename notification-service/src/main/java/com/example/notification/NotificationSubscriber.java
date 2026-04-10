package com.example.notification;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.google.cloud.spring.pubsub.core.PubSubTemplate;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.LinkedHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicLong;

@Service
public class NotificationSubscriber {

    private static final Logger log = LoggerFactory.getLogger(NotificationSubscriber.class);

    private final PubSubTemplate pubSubTemplate;
    private final ObjectMapper objectMapper;
    private final String subscriptionName;

    // ---- Metrics for throughput/latency demo ----
    private final AtomicLong messageCount = new AtomicLong(0);
    private final AtomicLong totalLatencyMs = new AtomicLong(0);
    private volatile LocalDateTime startTime;
    private final List<Map<String, String>> recentNotifications = new CopyOnWriteArrayList<>();

    public NotificationSubscriber(PubSubTemplate pubSubTemplate,
            @Value("${pubsub.subscription}") String subscriptionName) {
        this.pubSubTemplate = pubSubTemplate;
        this.subscriptionName = subscriptionName;
        this.objectMapper = new ObjectMapper();
        this.objectMapper.registerModule(new JavaTimeModule());
    }

    @PostConstruct
    public void subscribe() {
        startTime = LocalDateTime.now();
        log.info("====================================================");
        log.info("  NOTIFICATION SERVICE — subscribing to: {}", subscriptionName);
        log.info("====================================================");

        pubSubTemplate.subscribe(subscriptionName, message -> {

            long receiveTime = System.currentTimeMillis();
            String payload = message.getPubsubMessage().getData().toStringUtf8();
            String messageId = message.getPubsubMessage().getMessageId();

            try {
                TransactionEvent event = objectMapper.readValue(payload, TransactionEvent.class);
                // Calculate latency (publish → receive)
                long publishTime = message.getPubsubMessage().getPublishTime().getSeconds() * 1000;
                long latencyMs = receiveTime - publishTime;
                totalLatencyMs.addAndGet(latencyMs);
                long count = messageCount.incrementAndGet();

                log.info("-----------------------------------------------");
                log.info("  NOTIFICATION SENT!");
                log.info("  SMS to {}: You sent ₹{} to {}",
                        event.getSenderUpiId(), event.getAmount(), event.getReceiverUpiId());
                log.info("  SMS to {}: You received ₹{} from {}",
                        event.getReceiverUpiId(), event.getAmount(), event.getSenderUpiId());
                log.info("  TxnId: {} | MessageId: {} | Latency: {}ms",
                        event.getTransactionId(), messageId, latencyMs);
                log.info("  Total messages processed: {} | Avg latency: {}ms",
                        count, totalLatencyMs.get() / count);
                log.info("-----------------------------------------------");

                Map<String, String> notif = new LinkedHashMap<>();
                notif.put("txnId", String.valueOf(event.getTransactionId()));
                notif.put("sender", event.getSenderUpiId());
                notif.put("receiver", event.getReceiverUpiId());
                notif.put("amount", event.getAmount().toString());
                notif.put("time", java.time.LocalDateTime.now().format(java.time.format.DateTimeFormatter.ofPattern("HH:mm:ss")));
                recentNotifications.add(0, notif);
                if (recentNotifications.size() > 50) recentNotifications.remove(recentNotifications.size() - 1);

                message.ack();

            } catch (Exception e) {
                log.error("Failed to process message: {} | Error: {}", messageId, e.getMessage());
                message.nack();
            }
        });
    }

    // Expose metrics via REST
    public long getMessageCount() {
        return messageCount.get();
    }

    public long getAvgLatencyMs() {
        long count = messageCount.get();
        return count > 0 ? totalLatencyMs.get() / count : 0;
    }

    public LocalDateTime getStartTime() {
        return startTime;
    }

    public List<Map<String, String>> getRecentNotifications() { return recentNotifications; }
}
