package com.example.upi.config;

import com.google.cloud.spring.pubsub.PubSubAdmin;
import com.google.pubsub.v1.Subscription;
import com.google.pubsub.v1.Topic;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.CommandLineRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

@Configuration
@Profile("pubsub")
public class PubSubSetup {

    private static final Logger log = LoggerFactory.getLogger(PubSubSetup.class);

    @Value("${pubsub.topic:transaction-events}")
    private String mainTopic;

    @Value("${pubsub.subscriptions.notification:notification-sub}")
    private String notificationSub;

    @Value("${pubsub.subscriptions.fraud-detection:fraud-detection-sub}")
    private String fraudSub;

    @Value("${pubsub.subscriptions.analytics:analytics-sub}")
    private String analyticsSub;

    @Value("${pubsub.dlq.topic:transaction-events-dlq}")
    private String dlqTopic;

    @Value("${pubsub.dlq.subscription:dlq-monitor-sub}")
    private String dlqSub;

    @Bean
    CommandLineRunner setupPubSubResources(PubSubAdmin pubSubAdmin) {
        return args -> {
            log.info("========================================");
            log.info("  SETTING UP PUB/SUB RESOURCES");
            log.info("========================================");

            // Create main topic
            createTopicIfNotExists(pubSubAdmin, mainTopic);

            // Create DLQ topic
            createTopicIfNotExists(pubSubAdmin, dlqTopic);

            // Create fan-out subscriptions (all listen to same topic)
            createSubscriptionIfNotExists(pubSubAdmin, notificationSub, mainTopic);
            createSubscriptionIfNotExists(pubSubAdmin, fraudSub, mainTopic);
            createSubscriptionIfNotExists(pubSubAdmin, analyticsSub, mainTopic);

            // Create DLQ subscription
            createSubscriptionIfNotExists(pubSubAdmin, dlqSub, dlqTopic);

            log.info("========================================");
            log.info("  PUB/SUB SETUP COMPLETE");
            log.info("  Topic: {} → 3 subscriptions (fan-out)");
            log.info("  DLQ:   {} → 1 subscription", dlqTopic);
            log.info("========================================");
        };
    }

    private void createTopicIfNotExists(PubSubAdmin admin, String topicName) {
        try {
            Topic topic = admin.getTopic(topicName);
            if (topic != null) {
                log.info("  Topic already exists: {}", topicName);
            } else {
                admin.createTopic(topicName);
                log.info("  Created topic: {}", topicName);
            }
        } catch (Exception e) {
            try {
                admin.createTopic(topicName);
                log.info("  Created topic: {}", topicName);
            } catch (Exception ex) {
                log.warn("  Topic may already exist: {} ({})", topicName, ex.getMessage());
            }
        }
    }

    private void createSubscriptionIfNotExists(PubSubAdmin admin, String subName, String topicName) {
        try {
            Subscription sub = admin.getSubscription(subName);
            if (sub != null) {
                log.info("  Subscription already exists: {} → {}", subName, topicName);
            } else {
                admin.createSubscription(subName, topicName);
                log.info("  Created subscription: {} → {}", subName, topicName);
            }
        } catch (Exception e) {
            try {
                admin.createSubscription(subName, topicName);
                log.info("  Created subscription: {} → {}", subName, topicName);
            } catch (Exception ex) {
                log.warn("  Subscription may already exist: {} ({})", subName, ex.getMessage());
            }
        }
    }
}
