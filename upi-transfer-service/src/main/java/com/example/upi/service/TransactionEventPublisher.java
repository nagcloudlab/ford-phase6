package com.example.upi.service;

import com.example.upi.dto.TransactionEvent;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.cloud.spring.pubsub.core.PubSubTemplate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

@Service
public class TransactionEventPublisher {

    private static final Logger log = LoggerFactory.getLogger(TransactionEventPublisher.class);
    private static final String TOPIC = "transaction-events";

    private final PubSubTemplate pubSubTemplate;
    private final ObjectMapper objectMapper;

    public TransactionEventPublisher(PubSubTemplate pubSubTemplate,
            ObjectMapper objectMapper) {
        this.pubSubTemplate = pubSubTemplate;
        this.objectMapper = objectMapper;
    }

    public void publishTransactionEvent(TransactionEvent event) {
        try {
            String json = objectMapper.writeValueAsString(event);
            pubSubTemplate.publish(TOPIC, json)
                    .whenComplete((id, ex) -> {
                        if (ex != null) {
                            log.error("Failed to publish event: {}", event, ex);
                        } else {
                            log.info("Published event to {}: txnId={}, messageId={}",
                                    TOPIC, event.getTransactionId(), id);
                        }
                    });
        } catch (JsonProcessingException e) {
            log.error("Failed to serialize event: {}", event, e);
        }
    }
}