package com.example.upi.service;

import com.example.upi.dto.TransactionEvent;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.cloud.spring.pubsub.core.PubSubTemplate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;

import java.util.Map;

@Service
@Profile("pubsub")
public class TransactionEventPublisher {

    private static final Logger log = LoggerFactory.getLogger(TransactionEventPublisher.class);

    private final PubSubTemplate pubSubTemplate;
    private final ObjectMapper objectMapper;
    private final String topicName;

    public TransactionEventPublisher(PubSubTemplate pubSubTemplate,
            ObjectMapper objectMapper,
            @Value("${pubsub.topic:transaction-events}") String topicName) {
        this.pubSubTemplate = pubSubTemplate;
        this.objectMapper = objectMapper;
        this.topicName = topicName;
        log.info("TransactionEventPublisher initialized. Topic: {}", topicName);
    }

    public void publishTransactionEvent(TransactionEvent event) {
        try {
            String json = objectMapper.writeValueAsString(event);

            // Message attributes — used for filtering, routing, monitoring
            Map<String, String> attributes = Map.of(
                    "eventType", "TRANSFER",
                    "status", event.getStatus(),
                    "senderUpiId", event.getSenderUpiId(),
                    "receiverUpiId", event.getReceiverUpiId(),
                    "amountRange", categorizeAmount(event.getAmount().doubleValue())
            );

            pubSubTemplate.publish(topicName, json, attributes)
                    .whenComplete((id, ex) -> {
                        if (ex != null) {
                            log.error("PUBLISH FAILED | topic={} | txnId={} | error={}",
                                    topicName, event.getTransactionId(), ex.getMessage());
                        } else {
                            log.info("PUBLISHED | topic={} | txnId={} | messageId={} | from={} | to={} | amount={} | attributes={}",
                                    topicName, event.getTransactionId(), id,
                                    event.getSenderUpiId(), event.getReceiverUpiId(),
                                    event.getAmount(), attributes);
                        }
                    });
        } catch (JsonProcessingException e) {
            log.error("Failed to serialize event: {}", event, e);
        }
    }

    private String categorizeAmount(double amount) {
        if (amount >= 10000) return "HIGH";
        if (amount >= 1000) return "MEDIUM";
        return "LOW";
    }
}
