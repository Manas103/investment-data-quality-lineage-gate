package com.mfs.idqg.api.cache;

import com.mfs.idqg.api.store.BitemporalStore;
import com.mfs.idqg.api.store.Vintage;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Read-through cache over {@link BitemporalStore} for the as-of-now read path: a cache hit never
 * touches the store, a miss reads the store once and populates Redis before returning. Keyed on
 * the business key alone (not on an as-of-date), because the measured read path here is always
 * "latest as of now"; caching a specific historical as-of query would need a different key and a
 * different safety argument (a historical vintage never changes once recorded, so it would be
 * safe to cache indefinitely, unlike the latest-vintage key this class actually caches, which a
 * restatement can invalidate at any time).
 */
@Service
public class ReferenceDataCacheService {

    private static final String PREFIX = "idqg:latest:";
    private static final Duration TTL = Duration.ofSeconds(30);

    private final BitemporalStore store;
    private final StringRedisTemplate redis;

    public ReferenceDataCacheService(BitemporalStore store, StringRedisTemplate redis) {
        this.store = store;
        this.redis = redis;
    }

    /** Returns the latest known payload for {@code key}, serving from Redis on a cache hit. */
    public Map<String, String> readLatest(String key) {
        String cacheKey = PREFIX + key;
        Map<Object, Object> cached = redis.opsForHash().entries(cacheKey);
        if (!cached.isEmpty()) {
            Map<String, String> out = new LinkedHashMap<>();
            cached.forEach((k, v) -> out.put(String.valueOf(k), String.valueOf(v)));
            return out;
        }
        Vintage v = store.latest(key);
        if (v == null) {
            return null;
        }
        redis.opsForHash().putAll(cacheKey, v.payload());
        redis.expire(cacheKey, TTL);
        return v.payload();
    }

    /** Invalidates the cached "latest" entry for {@code key}; called by the RabbitMQ consumer. */
    public void invalidate(String key) {
        redis.delete(PREFIX + key);
    }

    public boolean isCached(String key) {
        return Boolean.TRUE.equals(redis.hasKey(PREFIX + key));
    }
}
