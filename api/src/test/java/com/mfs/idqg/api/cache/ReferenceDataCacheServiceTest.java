package com.mfs.idqg.api.cache;

import com.mfs.idqg.api.store.BitemporalStore;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.net.Socket;
import java.time.Instant;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Exercises the real read-through path against a real Redis (started in WSL2 Ubuntu for this
 * build, reached over the WSL2 localhost-forwarding loopback). Skips cleanly, rather than
 * failing the build, if Redis is not reachable on this machine right now, per the playbook's
 * precedent for infrastructure the project needs but does not own.
 */
class ReferenceDataCacheServiceTest {

    private LettuceConnectionFactory connectionFactory;
    private ReferenceDataCacheService cache;
    private BitemporalStore store;

    @BeforeEach
    void setUp() {
        Assumptions.assumeTrue(isReachable("localhost", 6379), "Redis is not reachable on localhost:6379");
        connectionFactory = new LettuceConnectionFactory(new RedisStandaloneConfiguration("localhost", 6379));
        connectionFactory.afterPropertiesSet();
        StringRedisTemplate redis = new StringRedisTemplate(connectionFactory);
        redis.afterPropertiesSet();
        store = new BitemporalStore();
        cache = new ReferenceDataCacheService(store, redis);
        redis.delete("idqg:latest:PRICE|INS-TEST|2027-01-01");
    }

    @AfterEach
    void tearDown() {
        if (connectionFactory != null) {
            connectionFactory.destroy();
        }
    }

    @Test
    void cacheMissReadsStoreAndPopulatesRedis() {
        store.restate("PRICE|INS-TEST|2027-01-01", Map.of("price", "123.45"), Instant.now());
        assertFalse(cache.isCached("PRICE|INS-TEST|2027-01-01"));

        Map<String, String> first = cache.readLatest("PRICE|INS-TEST|2027-01-01");
        assertEquals("123.45", first.get("price"));
        assertTrue(cache.isCached("PRICE|INS-TEST|2027-01-01"), "a read must populate the cache on a miss");
    }

    @Test
    void cacheHitServesWithoutChangingTheStore() {
        store.restate("PRICE|INS-TEST|2027-01-01", Map.of("price", "50.00"), Instant.now());
        cache.readLatest("PRICE|INS-TEST|2027-01-01"); // populate
        store.restate("PRICE|INS-TEST|2027-01-01", Map.of("price", "999.99"), Instant.now());

        Map<String, String> stillCached = cache.readLatest("PRICE|INS-TEST|2027-01-01");
        assertEquals("50.00", stillCached.get("price"), "a cache hit must serve the cached value, not the newer store value");
    }

    @Test
    void invalidateForcesTheNextReadBackToTheStore() {
        store.restate("PRICE|INS-TEST|2027-01-01", Map.of("price", "50.00"), Instant.now());
        cache.readLatest("PRICE|INS-TEST|2027-01-01");
        store.restate("PRICE|INS-TEST|2027-01-01", Map.of("price", "999.99"), Instant.now());

        cache.invalidate("PRICE|INS-TEST|2027-01-01");
        Map<String, String> afterInvalidate = cache.readLatest("PRICE|INS-TEST|2027-01-01");
        assertEquals("999.99", afterInvalidate.get("price"));
    }

    private static boolean isReachable(String host, int port) {
        try (Socket s = new Socket()) {
            s.connect(new java.net.InetSocketAddress(host, port), 500);
            return true;
        } catch (Exception e) {
            return false;
        }
    }
}
