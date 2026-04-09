package com.example.upi.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;

@Service
public class RateLimiterService {

    private static final Logger log = LoggerFactory.getLogger(RateLimiterService.class);
    private static final String RATE_PREFIX = "ratelimit:transfer:";

    private final RedisTemplate<String, Object> redisTemplate;
    private final int maxTransfersPerWindow;
    private final Duration window;

    public RateLimiterService(RedisTemplate<String, Object> redisTemplate,
                              @Value("${redis.rate-limit.max-transfers:10}") int maxTransfersPerWindow,
                              @Value("${redis.rate-limit.window-seconds:60}") int windowSeconds) {
        this.redisTemplate = redisTemplate;
        this.maxTransfersPerWindow = maxTransfersPerWindow;
        this.window = Duration.ofSeconds(windowSeconds);
    }

    /**
     * Check if the sender is allowed to make a transfer.
     * Uses Redis INCR + EXPIRE for a sliding window counter.
     *
     * @return true if allowed, false if rate limit exceeded
     */
    public boolean isAllowed(String senderUpiId) {
        String key = RATE_PREFIX + senderUpiId;
        try {
            Long count = redisTemplate.opsForValue().increment(key);
            if (count == null) {
                return true; // Redis issue, fail open
            }

            // First request in window — set the expiry
            if (count == 1) {
                redisTemplate.expire(key, window);
            }

            boolean allowed = count <= maxTransfersPerWindow;
            log.info("RATE LIMIT | user={} | count={}/{} | window={}s | allowed={}",
                    senderUpiId, count, maxTransfersPerWindow, window.getSeconds(), allowed);
            return allowed;

        } catch (Exception e) {
            log.warn("Rate limit check failed for {}: {} — allowing request", senderUpiId, e.getMessage());
            return true; // Fail open: if Redis is down, don't block transfers
        }
    }

    /**
     * Get remaining transfers for a user in current window.
     */
    public int getRemainingTransfers(String senderUpiId) {
        String key = RATE_PREFIX + senderUpiId;
        try {
            Object val = redisTemplate.opsForValue().get(key);
            if (val == null) return maxTransfersPerWindow;
            int used = ((Number) val).intValue();
            return Math.max(0, maxTransfersPerWindow - used);
        } catch (Exception e) {
            return maxTransfersPerWindow;
        }
    }
}
