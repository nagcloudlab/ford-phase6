package com.example.analytics;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.google.cloud.spring.pubsub.core.PubSubTemplate;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

@Service
public class AnalyticsSubscriber {

    private static final Logger log = LoggerFactory.getLogger(AnalyticsSubscriber.class);

    private final PubSubTemplate pubSubTemplate;
    private final ObjectMapper objectMapper;
    private final String subscriptionName;

    // ---- Real-time analytics ----
    private final AtomicLong messageCount = new AtomicLong(0);
    private final AtomicLong totalLatencyMs = new AtomicLong(0);
    private volatile BigDecimal totalAmount = BigDecimal.ZERO;
    private volatile BigDecimal maxTransaction = BigDecimal.ZERO;
    private volatile BigDecimal minTransaction = BigDecimal.valueOf(Long.MAX_VALUE);
    private final Map<String, AtomicLong> topSenders = new ConcurrentHashMap<>();
    private final Map<String, AtomicLong> topReceivers = new ConcurrentHashMap<>();
    private final Map<String, AtomicLong> amountBuckets = new ConcurrentHashMap<>(Map.of(
            "LOW (<1000)", new AtomicLong(0),
            "MEDIUM (1000-5000)", new AtomicLong(0),
            "HIGH (>5000)", new AtomicLong(0)
    ));
    private volatile LocalDateTime startTime;

    public AnalyticsSubscriber(PubSubTemplate pubSubTemplate,
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
        log.info("  ANALYTICS SERVICE — subscribing to: {}", subscriptionName);
        log.info("  Tracking: volume, amounts, top users, latency");
        log.info("====================================================");

        pubSubTemplate.subscribe(subscriptionName, message -> {
            long receiveTime = System.currentTimeMillis();
            String payload = message.getPubsubMessage().getData().toStringUtf8();
            String messageId = message.getPubsubMessage().getMessageId();

            try {
                TransactionEvent event = objectMapper.readValue(payload, TransactionEvent.class);

                long publishTime = message.getPubsubMessage().getPublishTime().getSeconds() * 1000;
                long latencyMs = receiveTime - publishTime;
                totalLatencyMs.addAndGet(latencyMs);
                long count = messageCount.incrementAndGet();

                // Update analytics
                synchronized (this) {
                    totalAmount = totalAmount.add(event.getAmount());
                    if (event.getAmount().compareTo(maxTransaction) > 0) {
                        maxTransaction = event.getAmount();
                    }
                    if (event.getAmount().compareTo(minTransaction) < 0) {
                        minTransaction = event.getAmount();
                    }
                }

                // Track top senders/receivers
                topSenders.computeIfAbsent(event.getSenderUpiId(), k -> new AtomicLong(0)).incrementAndGet();
                topReceivers.computeIfAbsent(event.getReceiverUpiId(), k -> new AtomicLong(0)).incrementAndGet();

                // Bucket by amount
                double amt = event.getAmount().doubleValue();
                if (amt >= 5000) amountBuckets.get("HIGH (>5000)").incrementAndGet();
                else if (amt >= 1000) amountBuckets.get("MEDIUM (1000-5000)").incrementAndGet();
                else amountBuckets.get("LOW (<1000)").incrementAndGet();

                BigDecimal avgAmount = totalAmount.divide(BigDecimal.valueOf(count), 2, RoundingMode.HALF_UP);

                log.info("-----------------------------------------------");
                log.info("  ANALYTICS RECORDED | TxnId: {}", event.getTransactionId());
                log.info("  {} → {} | ₹{}", event.getSenderUpiId(), event.getReceiverUpiId(), event.getAmount());
                log.info("  Running totals:");
                log.info("    Transactions: {} | Total volume: ₹{}", count, totalAmount);
                log.info("    Avg: ₹{} | Min: ₹{} | Max: ₹{}", avgAmount, minTransaction, maxTransaction);
                log.info("    Latency: {}ms | Avg latency: {}ms", latencyMs, totalLatencyMs.get() / count);
                log.info("-----------------------------------------------");

                message.ack();

            } catch (Exception e) {
                log.error("Failed to process message: {} | Error: {}", messageId, e.getMessage());
                message.nack();
            }
        });
    }

    // Expose for StatsController
    public long getMessageCount() { return messageCount.get(); }
    public long getAvgLatencyMs() {
        long count = messageCount.get();
        return count > 0 ? totalLatencyMs.get() / count : 0;
    }
    public LocalDateTime getStartTime() { return startTime; }
    public BigDecimal getTotalAmount() { return totalAmount; }
    public BigDecimal getMaxTransaction() { return maxTransaction; }
    public BigDecimal getMinTransaction() {
        return minTransaction.compareTo(BigDecimal.valueOf(Long.MAX_VALUE)) == 0 ? BigDecimal.ZERO : minTransaction;
    }
    public Map<String, AtomicLong> getTopSenders() { return topSenders; }
    public Map<String, AtomicLong> getTopReceivers() { return topReceivers; }
    public Map<String, AtomicLong> getAmountBuckets() { return amountBuckets; }
}
