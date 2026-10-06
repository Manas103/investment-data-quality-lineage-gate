package com.mfs.idqg.api.web;

import com.mfs.idqg.api.cache.ReferenceDataCacheService;
import com.mfs.idqg.api.changefeed.CacheInvalidationConsumer;
import com.mfs.idqg.api.changefeed.ChangeFeedPublisher;
import com.mfs.idqg.api.store.BitemporalStore;
import com.mfs.idqg.api.store.Vintage;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * The as-of-date read path over security master, pricing and positions, plus the restatement
 * endpoint that writes a new vintage and pushes it onto the change feed. {@code /raw} endpoints
 * bypass the Redis cache entirely (used by the polling-baseline side of
 * {@code ChangeFeedLatencyBenchmark}); {@code /cached} endpoints are the real read-through path
 * ({@code /pricing/latest} is also what {@code CacheLatencyBenchmark} drives at load). The
 * {@code _bench} endpoints exist only so the two benchmarks, run as separate JVMs against this
 * process over HTTP, can read internal timing state; they are not part of the product API.
 */
@RestController
@RequestMapping("/api")
public class ReferenceDataController {

    private final BitemporalStore store;
    private final ReferenceDataCacheService cache;
    private final ChangeFeedPublisher publisher;
    private final CacheInvalidationConsumer consumer;

    public ReferenceDataController(BitemporalStore store, ReferenceDataCacheService cache,
                                    ChangeFeedPublisher publisher, CacheInvalidationConsumer consumer) {
        this.store = store;
        this.cache = cache;
        this.publisher = publisher;
        this.consumer = consumer;
    }

    @GetMapping("/security-master/{instrumentId}/cached")
    public Map<String, String> securityMasterCached(@PathVariable String instrumentId) {
        return orNotFound(cache.readLatest("SM|" + instrumentId));
    }

    @GetMapping("/pricing/{instrumentId}/{date}/raw")
    public Map<String, String> pricingRaw(@PathVariable String instrumentId, @PathVariable String date) {
        Vintage v = store.latest("PRICE|" + instrumentId + "|" + date);
        return orNotFound(v == null ? null : v.payload());
    }

    @GetMapping("/pricing/{instrumentId}/{date}/cached")
    public Map<String, String> pricingCached(@PathVariable String instrumentId, @PathVariable String date) {
        return orNotFound(cache.readLatest("PRICE|" + instrumentId + "|" + date));
    }

    @GetMapping("/positions/{fundId}/{instrumentId}/{date}/cached")
    public Map<String, String> positionCached(@PathVariable String fundId, @PathVariable String instrumentId,
                                               @PathVariable String date) {
        return orNotFound(cache.readLatest("POS|" + fundId + "|" + instrumentId + "|" + date));
    }

    @PostMapping("/restatements/pricing/{instrumentId}/{date}")
    public Map<String, Object> restatePricing(@PathVariable String instrumentId, @PathVariable String date,
                                               @RequestBody RestatementRequest body) {
        String key = "PRICE|" + instrumentId + "|" + date;
        int vintagesBefore = store.vintageCount(key);
        Instant now = Instant.now();
        store.restate(key, Map.of("instrument_id", instrumentId, "price_date", date,
                "price", Double.toString(body.price())), now);
        publisher.publishChange(key, now.toEpochMilli());
        return Map.of("key", key, "vintagesBefore", vintagesBefore, "vintagesAfter", store.vintageCount(key));
    }

    @GetMapping("/_bench/changefeed/latencies")
    public List<Long> benchChangeFeedLatencies() {
        return consumer.deliveryLatenciesMillis();
    }

    @GetMapping("/_bench/changefeed/reset")
    public void benchChangeFeedReset() {
        consumer.deliveryLatenciesMillis().clear();
    }

    @GetMapping("/_bench/store/key-count")
    public int benchKeyCount() {
        return store.keyCount();
    }

    private Map<String, String> orNotFound(Map<String, String> payload) {
        if (payload == null) {
            throw new NoSuchKeyException();
        }
        return payload;
    }

    @ResponseStatus(org.springframework.http.HttpStatus.NOT_FOUND)
    static class NoSuchKeyException extends RuntimeException {
    }
}
