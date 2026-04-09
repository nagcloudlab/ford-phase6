package com.example.analytics;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.google.cloud.spring.pubsub.core.PubSubTemplate;
import com.google.cloud.spring.pubsub.support.converter.JacksonPubSubMessageConverter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import jakarta.annotation.PostConstruct;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

@Service
public class AnalyticsSubscriber {

    private static final Logger log = LoggerFactory.getLogger(AnalyticsSubscriber.class);

    private final PubSubTemplate pubSubTemplate;
    private final BigQueryService bigQueryService;
    private final String subscriptionName;
    private final ObjectMapper objectMapper;

    // In-memory real-time stats (same as original analytics-service)
    private final AtomicLong messageCount = new AtomicLong(0);
    private final AtomicLong totalLatencyMs = new AtomicLong(0);
    private BigDecimal totalAmount = BigDecimal.ZERO;
    private BigDecimal maxTransaction = BigDecimal.ZERO;
    private BigDecimal minTransaction = new BigDecimal("999999999");
    private final Map<String, AtomicLong> topSenders = new ConcurrentHashMap<>();
    private final Map<String, AtomicLong> topReceivers = new ConcurrentHashMap<>();
    private final Map<String, AtomicLong> amountBuckets = new ConcurrentHashMap<>(Map.of(
            "LOW (<1000)", new AtomicLong(0),
            "MEDIUM (1000-5000)", new AtomicLong(0),
            "HIGH (>5000)", new AtomicLong(0)
    ));
    private Instant startTime;

    public AnalyticsSubscriber(PubSubTemplate pubSubTemplate,
                               BigQueryService bigQueryService,
                               @Value("${pubsub.subscription:analytics-sub}") String subscriptionName) {
        this.pubSubTemplate = pubSubTemplate;
        this.bigQueryService = bigQueryService;
        this.subscriptionName = subscriptionName;
        this.objectMapper = new ObjectMapper();
        this.objectMapper.registerModule(new JavaTimeModule());
    }

    @PostConstruct
    public void subscribe() {
        startTime = Instant.now();
        log.info("Subscribing to: {} (with BigQuery streaming)", subscriptionName);

        pubSubTemplate.subscribe(subscriptionName, message -> {
            try {
                String payload = message.getPubsubMessage().getData().toStringUtf8();
                TransactionEvent event = objectMapper.readValue(payload, TransactionEvent.class);

                long count = messageCount.incrementAndGet();

                // Calculate processing latency
                long latencyMs = 0;
                if (event.getTimestamp() != null) {
                    latencyMs = Duration.between(event.getTimestamp(), LocalDateTime.now()).toMillis();
                    totalLatencyMs.addAndGet(latencyMs);
                }

                // Update in-memory stats
                updateStats(event);

                // Stream to BigQuery
                bigQueryService.streamTransaction(event);

                log.info("PROCESSED #{} | txnId={} | {} -> {} | amount={} | latency={}ms | bqRows={}",
                        count, event.getTransactionId(),
                        event.getSenderUpiId(), event.getReceiverUpiId(),
                        event.getAmount(), latencyMs, bigQueryService.getRowsInserted());

                message.ack();
            } catch (Exception e) {
                log.error("Failed to process message: {}", e.getMessage(), e);
                message.nack();
            }
        });
    }

    private synchronized void updateStats(TransactionEvent event) {
        BigDecimal amount = event.getAmount();
        totalAmount = totalAmount.add(amount);

        if (amount.compareTo(maxTransaction) > 0) maxTransaction = amount;
        if (amount.compareTo(minTransaction) < 0) minTransaction = amount;

        topSenders.computeIfAbsent(event.getSenderUpiId(), k -> new AtomicLong(0)).incrementAndGet();
        topReceivers.computeIfAbsent(event.getReceiverUpiId(), k -> new AtomicLong(0)).incrementAndGet();

        if (amount.compareTo(new BigDecimal("5000")) > 0) {
            amountBuckets.get("HIGH (>5000)").incrementAndGet();
        } else if (amount.compareTo(new BigDecimal("1000")) >= 0) {
            amountBuckets.get("MEDIUM (1000-5000)").incrementAndGet();
        } else {
            amountBuckets.get("LOW (<1000)").incrementAndGet();
        }
    }

    // Getters for StatsController
    public long getMessageCount() { return messageCount.get(); }
    public long getTotalLatencyMs() { return totalLatencyMs.get(); }
    public BigDecimal getTotalAmount() { return totalAmount; }
    public BigDecimal getMaxTransaction() { return maxTransaction; }
    public BigDecimal getMinTransaction() { return minTransaction; }
    public Map<String, AtomicLong> getTopSenders() { return topSenders; }
    public Map<String, AtomicLong> getTopReceivers() { return topReceivers; }
    public Map<String, AtomicLong> getAmountBuckets() { return amountBuckets; }
    public Instant getStartTime() { return startTime; }
}
