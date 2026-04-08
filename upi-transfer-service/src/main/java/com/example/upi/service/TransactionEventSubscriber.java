package com.example.upi.service;

import com.google.cloud.spring.pubsub.core.PubSubTemplate;
import com.google.cloud.spring.pubsub.support.BasicAcknowledgeablePubsubMessage;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

@Service
public class TransactionEventSubscriber {
    private static final Logger log = LoggerFactory.getLogger(TransactionEventSubscriber.class);
    private static final String SUBSCRIPTION = "transaction-processor";

    private final PubSubTemplate pubSubTemplate;

    public TransactionEventSubscriber(PubSubTemplate pubSubTemplate) {
        this.pubSubTemplate = pubSubTemplate;
    }

    @PostConstruct
    public void subscribe() {
        log.info("Subscribing to: {}", SUBSCRIPTION);

        pubSubTemplate.subscribe(SUBSCRIPTION, message -> {
            String payload = message.getPubsubMessage()
                    .getData()
                    .toStringUtf8();

            log.info("Received transaction event: {}", payload);

            // In a real app, this would:
            // - Send push notification to user
            // - Update fraud detection system
            // - Write to analytics pipeline

            // Acknowledge the message
            message.ack();
            log.info("Message acknowledged: {}", message.getPubsubMessage().getMessageId());
        });
    }
}