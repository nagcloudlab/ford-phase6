package com.example.fraud;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.google.cloud.spring.pubsub.core.PubSubTemplate;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicLong;

@Service
public class FraudDetectionSubscriber {

    private static final Logger log = LoggerFactory.getLogger(FraudDetectionSubscriber.class);

    private final PubSubTemplate pubSubTemplate;
    private final ObjectMapper objectMapper;
    private final String subscriptionName;
    private final BigDecimal highAmountThreshold;

    // ---- Metrics ----
    private final AtomicLong messageCount = new AtomicLong(0);
    private final AtomicLong alertCount = new AtomicLong(0);
    private final AtomicLong totalLatencyMs = new AtomicLong(0);
    private volatile LocalDateTime startTime;

    // Track recent transfers per sender (for rapid-fire detection)
    private final Map<String, List<LocalDateTime>> recentTransfers = new ConcurrentHashMap<>();

    @Value("${fraud.rapid-transfer-max-count:3}")
    private int rapidTransferMaxCount;

    @Value("${fraud.rapid-transfer-window-seconds:60}")
    private int rapidTransferWindowSeconds;

    public FraudDetectionSubscriber(PubSubTemplate pubSubTemplate,
            @Value("${pubsub.subscription}") String subscriptionName,
            @Value("${fraud.high-amount-threshold:5000}") double highAmountThreshold) {
        this.pubSubTemplate = pubSubTemplate;
        this.subscriptionName = subscriptionName;
        this.highAmountThreshold = BigDecimal.valueOf(highAmountThreshold);
        this.objectMapper = new ObjectMapper();
        this.objectMapper.registerModule(new JavaTimeModule());
    }

    @PostConstruct
    public void subscribe() {
        startTime = LocalDateTime.now();
        log.info("====================================================");
        log.info("  FRAUD DETECTION SERVICE — subscribing to: {}", subscriptionName);
        log.info("  High amount threshold: ₹{}", highAmountThreshold);
        log.info("  Rapid transfer: >{} in {}s", rapidTransferMaxCount, rapidTransferWindowSeconds);
        log.info("====================================================");

        pubSubTemplate.subscribe(subscriptionName, message -> {
            long receiveTime = System.currentTimeMillis();
            String payload = message.getPubsubMessage().getData().toStringUtf8();
            String messageId = message.getPubsubMessage().getMessageId();

            // Read message attributes (set by publisher)
            Map<String, String> attributes = message.getPubsubMessage().getAttributesMap();

            try {
                TransactionEvent event = objectMapper.readValue(payload, TransactionEvent.class);

                long publishTime = message.getPubsubMessage().getPublishTime().getSeconds() * 1000;
                long latencyMs = receiveTime - publishTime;
                totalLatencyMs.addAndGet(latencyMs);
                long count = messageCount.incrementAndGet();

                log.info("-----------------------------------------------");
                log.info("  FRAUD CHECK | TxnId: {} | {} → {} | ₹{}",
                        event.getTransactionId(), event.getSenderUpiId(),
                        event.getReceiverUpiId(), event.getAmount());
                log.info("  Attributes: {}", attributes);

                // ---- Rule 1: High amount detection ----
                boolean isHighAmount = event.getAmount().compareTo(highAmountThreshold) >= 0;
                if (isHighAmount) {
                    alertCount.incrementAndGet();
                    log.warn("  ALERT: HIGH VALUE TRANSACTION! ₹{} exceeds threshold ₹{}",
                            event.getAmount(), highAmountThreshold);
                    log.warn("  Action: Flagged for manual review");
                }

                // ---- Rule 2: Rapid-fire transfer detection ----
                String sender = event.getSenderUpiId();
                recentTransfers.computeIfAbsent(sender, k -> new CopyOnWriteArrayList<>());
                List<LocalDateTime> transfers = recentTransfers.get(sender);
                transfers.add(LocalDateTime.now());

                // Clean old entries
                LocalDateTime cutoff = LocalDateTime.now().minusSeconds(rapidTransferWindowSeconds);
                transfers.removeIf(t -> t.isBefore(cutoff));

                if (transfers.size() > rapidTransferMaxCount) {
                    alertCount.incrementAndGet();
                    log.warn("  ALERT: RAPID TRANSFERS! {} made {} transfers in {}s window",
                            sender, transfers.size(), rapidTransferWindowSeconds);
                    log.warn("  Action: Account temporarily flagged");
                }

                if (!isHighAmount && transfers.size() <= rapidTransferMaxCount) {
                    log.info("  Result: CLEAN — no fraud indicators");
                }

                log.info("  Latency: {}ms | Total processed: {} | Alerts: {}",
                        latencyMs, count, alertCount.get());
                log.info("-----------------------------------------------");

                message.ack();

            } catch (Exception e) {
                log.error("Failed to process message: {} | Error: {}", messageId, e.getMessage());
                message.nack();
            }
        });
    }

    public long getMessageCount() { return messageCount.get(); }
    public long getAlertCount() { return alertCount.get(); }
    public long getAvgLatencyMs() {
        long count = messageCount.get();
        return count > 0 ? totalLatencyMs.get() / count : 0;
    }
    public LocalDateTime getStartTime() { return startTime; }
}
