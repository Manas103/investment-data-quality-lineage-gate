package com.mfs.idqg.ingestion;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.Set;

/** A small reference instrument master, so the orphan-reference health check has something real to check against. */
public final class InstrumentMaster {

    private static final String[] CURRENCIES = {"USD", "EUR", "GBP", "JPY", "CHF"};
    private static final String[] REGIONS = {"AMER", "EMEA", "APAC"};

    private final List<String> instrumentIds;
    private final Set<String> validInstrumentIds;

    public InstrumentMaster(int count) {
        List<String> ids = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            ids.add(String.format("INST-%05d", i + 1));
        }
        this.instrumentIds = List.copyOf(ids);
        this.validInstrumentIds = Set.copyOf(ids);
    }

    public List<String> instrumentIds() {
        return instrumentIds;
    }

    public boolean isValid(String instrumentId) {
        return validInstrumentIds.contains(instrumentId);
    }

    public static String currencyFor(Random random) {
        return CURRENCIES[random.nextInt(CURRENCIES.length)];
    }

    public static String regionFor(Random random) {
        return REGIONS[random.nextInt(REGIONS.length)];
    }

    public static boolean isKnownCurrency(String code) {
        for (String c : CURRENCIES) {
            if (c.equals(code)) {
                return true;
            }
        }
        return false;
    }
}
