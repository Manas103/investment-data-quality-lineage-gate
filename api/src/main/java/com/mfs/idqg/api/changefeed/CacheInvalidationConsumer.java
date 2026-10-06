package com.mfs.idqg.api.changefeed;

import com.mfs.idqg.api.cache.ReferenceDataCacheService;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;

import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Consumes the change feed and invalidates the Redis entry for the key that changed, so the next
 * read is forced to re-fetch the new vintage instead of serving a stale cache hit. Also records
 * each message's own delivery latency (now minus the timestamp carried in the message) for
 * {@code ChangeFeedLatencyBenchmark}.
 */
@Component
public class CacheInvalidationConsumer {

    private final ReferenceDataCacheService cache;
    private final CopyOnWriteArrayList<Long> deliveryLatenciesMillis = new CopyOnWriteArrayList<>();

    public CacheInvalidationConsumer(ReferenceDataCacheService cache) {
        this.cache = cache;
    }

    @RabbitListener(queues = ChangeFeedConfig.QUEUE_NAME)
    public void onChange(String message) {
        int sep = message.lastIndexOf('|');
        String key = message.substring(0, sep);
        long changedAtEpochMillis = Long.parseLong(message.substring(sep + 1));
        deliveryLatenciesMillis.add(System.currentTimeMillis() - changedAtEpochMillis);
        cache.invalidate(key);
    }

    public CopyOnWriteArrayList<Long> deliveryLatenciesMillis() {
        return deliveryLatenciesMillis;
    }
}
