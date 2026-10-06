package com.mfs.idqg.api.store;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * The claim: bitemporal vintages a restatement adds to instead of overwriting. Checked directly:
 * a second restatement for the same key must not remove or mutate the first vintage, and a query
 * as of a system time before the second restatement must still see the first vintage's value.
 */
class BitemporalStoreTest {

    @Test
    void restatementAddsAVintageRatherThanOverwriting() {
        BitemporalStore store = new BitemporalStore();
        Instant t1 = Instant.parse("2027-01-01T00:00:00Z");
        Instant t2 = t1.plus(1, ChronoUnit.DAYS);

        store.restate("PRICE|INS-0001|2027-01-01", Map.of("price", "100.00"), t1);
        assertEquals(1, store.vintageCount("PRICE|INS-0001|2027-01-01"));

        store.restate("PRICE|INS-0001|2027-01-01", Map.of("price", "107.50"), t2);
        assertEquals(2, store.vintageCount("PRICE|INS-0001|2027-01-01"),
                "a restatement must add a vintage, not replace the existing one");

        assertEquals("100.00", store.asOf("PRICE|INS-0001|2027-01-01", t1).payload().get("price"),
                "the original vintage must still be visible as of its own recorded time");
        assertEquals("100.00",
                store.asOf("PRICE|INS-0001|2027-01-01", t2.minus(1, ChronoUnit.SECONDS)).payload().get("price"),
                "a system time just before the restatement must still see the pre-restatement value");
        assertEquals("107.50", store.asOf("PRICE|INS-0001|2027-01-01", t2).payload().get("price"),
                "a system time at or after the restatement must see the corrected value");
        assertEquals("107.50", store.latest("PRICE|INS-0001|2027-01-01").payload().get("price"));
    }

    @Test
    void asOfBeforeAnyVintageReturnsNull() {
        BitemporalStore store = new BitemporalStore();
        Instant t1 = Instant.parse("2027-01-01T00:00:00Z");
        store.restate("PRICE|INS-0002|2027-01-01", Map.of("price", "50.00"), t1);
        assertNull(store.asOf("PRICE|INS-0002|2027-01-01", t1.minus(1, ChronoUnit.DAYS)));
    }

    @Test
    void threeRestatementsAllRemainQueryable() {
        BitemporalStore store = new BitemporalStore();
        String key = "PRICE|INS-0003|2027-01-01";
        Instant t1 = Instant.parse("2027-01-01T00:00:00Z");
        Instant t2 = t1.plus(1, ChronoUnit.DAYS);
        Instant t3 = t1.plus(2, ChronoUnit.DAYS);
        store.restate(key, Map.of("price", "10.00"), t1);
        store.restate(key, Map.of("price", "20.00"), t2);
        store.restate(key, Map.of("price", "30.00"), t3);

        assertEquals(3, store.vintageCount(key));
        assertEquals("10.00", store.asOf(key, t1).payload().get("price"));
        assertEquals("20.00", store.asOf(key, t2).payload().get("price"));
        assertEquals("30.00", store.asOf(key, t3).payload().get("price"));
    }
}
