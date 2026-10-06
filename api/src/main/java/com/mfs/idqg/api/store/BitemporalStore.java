package com.mfs.idqg.api.store;

import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * An in-memory bitemporal store keyed by business key (instrument, instrument+date, or
 * fund+instrument+date). Every write via {@link #restate} appends a new {@link Vintage} rather
 * than overwriting the prior one, so a restatement of a security-master row, a price or a
 * position never loses the value that was true before the correction was recorded. This is the
 * data structure behind the "bitemporal vintages a restatement adds to instead of overwriting"
 * claim; {@code BitemporalStoreTest} checks it directly.
 */
@Component
public class BitemporalStore {

    private final Map<String, List<Vintage>> vintagesByKey = new ConcurrentHashMap<>();

    /** Appends a new vintage for {@code key}. Never removes or mutates an existing vintage. */
    public synchronized void restate(String key, Map<String, String> payload, Instant recordedAt) {
        List<Vintage> list = vintagesByKey.computeIfAbsent(key, k -> new ArrayList<>());
        list.add(new Vintage(recordedAt, payload));
    }

    /** The vintage whose recordedAt is the latest one at or before {@code systemTime}, or null. */
    public Vintage asOf(String key, Instant systemTime) {
        List<Vintage> list = vintagesByKey.get(key);
        if (list == null) {
            return null;
        }
        Vintage best = null;
        synchronized (this) {
            for (Vintage v : list) {
                if (!v.recordedAt().isAfter(systemTime) && (best == null || v.recordedAt().isAfter(best.recordedAt()))) {
                    best = v;
                }
            }
        }
        return best;
    }

    /** The vintage with the latest recordedAt for {@code key} (the one a restatement just added), or null. */
    public synchronized Vintage latest(String key) {
        List<Vintage> list = vintagesByKey.get(key);
        if (list == null || list.isEmpty()) {
            return null;
        }
        Vintage best = list.get(0);
        for (Vintage v : list) {
            if (v.recordedAt().isAfter(best.recordedAt())) {
                best = v;
            }
        }
        return best;
    }

    /** Number of vintages ever recorded for {@code key} (grows by one on every restatement). */
    public int vintageCount(String key) {
        List<Vintage> list = vintagesByKey.get(key);
        return list == null ? 0 : list.size();
    }

    public int keyCount() {
        return vintagesByKey.size();
    }
}
