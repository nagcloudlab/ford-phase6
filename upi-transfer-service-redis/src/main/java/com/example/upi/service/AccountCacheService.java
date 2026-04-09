package com.example.upi.service;

import com.example.upi.model.Account;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.Optional;

@Service
public class AccountCacheService {

    private static final Logger log = LoggerFactory.getLogger(AccountCacheService.class);
    private static final String CACHE_PREFIX = "account:";
    private static final Duration TTL = Duration.ofMinutes(5);

    private final RedisTemplate<String, Object> redisTemplate;

    public AccountCacheService(RedisTemplate<String, Object> redisTemplate) {
        this.redisTemplate = redisTemplate;
    }

    /**
     * Try to get an account from Redis cache.
     */
    public Optional<Account> getFromCache(String upiId) {
        try {
            Object cached = redisTemplate.opsForValue().get(CACHE_PREFIX + upiId);
            if (cached != null) {
                log.info("CACHE HIT | key={}{}", CACHE_PREFIX, upiId);
                if (cached instanceof Account account) {
                    return Optional.of(account);
                }
                // Handle LinkedHashMap from JSON deserialization
                @SuppressWarnings("unchecked")
                java.util.Map<String, Object> map = (java.util.Map<String, Object>) cached;
                Account account = new Account();
                if (map.get("id") != null) account.setId(((Number) map.get("id")).longValue());
                account.setUpiId((String) map.get("upiId"));
                account.setHolderName((String) map.get("holderName"));
                account.setBalance(new java.math.BigDecimal(map.get("balance").toString()));
                return Optional.of(account);
            }
            log.info("CACHE MISS | key={}{}", CACHE_PREFIX, upiId);
        } catch (Exception e) {
            log.warn("Cache read failed for {}: {}", upiId, e.getMessage());
        }
        return Optional.empty();
    }

    /**
     * Store an account in Redis cache with TTL.
     */
    public void putInCache(Account account) {
        try {
            redisTemplate.opsForValue().set(CACHE_PREFIX + account.getUpiId(), account, TTL);
            log.info("CACHED | key={}{} | ttl={}s", CACHE_PREFIX, account.getUpiId(), TTL.getSeconds());
        } catch (Exception e) {
            log.warn("Cache write failed for {}: {}", account.getUpiId(), e.getMessage());
        }
    }

    /**
     * Evict an account from Redis cache (called after balance changes).
     */
    public void evictFromCache(String upiId) {
        try {
            Boolean deleted = redisTemplate.delete(CACHE_PREFIX + upiId);
            log.info("CACHE EVICT | key={}{} | deleted={}", CACHE_PREFIX, upiId, deleted);
        } catch (Exception e) {
            log.warn("Cache evict failed for {}: {}", upiId, e.getMessage());
        }
    }
}
