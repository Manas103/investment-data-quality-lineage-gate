package com.mfs.idqg.api.store;

import java.time.Instant;
import java.util.Map;

/**
 * One immutable version of a business key's data, stamped with the system time it was recorded.
 * A restatement never mutates or replaces this object; it appends a new one with a later
 * recordedAt. {@link BitemporalStore#asOf} picks the vintage whose recordedAt is the latest one
 * not after the query's system time, which is what makes "what did we believe as of system time
 * T" answerable after any number of later restatements.
 */
public record Vintage(Instant recordedAt, Map<String, String> payload) {
}
