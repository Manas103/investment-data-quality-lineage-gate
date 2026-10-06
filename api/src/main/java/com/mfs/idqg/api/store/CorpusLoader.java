package com.mfs.idqg.api.store;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Reads the generator's security_master.csv, pricing.csv and positions.csv (the same corpus
 * format the idqg validation engine in {@code service/} reads) and seeds a {@link BitemporalStore}
 * with one initial vintage per row, all stamped with the same {@code ingestedAt} instant. No
 * third-party CSV library; the generator emits no embedded commas or quoting, the same
 * simplification the rest of this repository already discloses.
 */
public final class CorpusLoader {

    private CorpusLoader() {
    }

    public static int loadSecurityMaster(Path csv, BitemporalStore store, Instant ingestedAt) throws IOException {
        int n = 0;
        for (Map<String, String> row : read(csv)) {
            store.restate("SM|" + row.get("instrument_id"), row, ingestedAt);
            n++;
        }
        return n;
    }

    public static int loadPricing(Path csv, BitemporalStore store, Instant ingestedAt) throws IOException {
        int n = 0;
        for (Map<String, String> row : read(csv)) {
            String key = "PRICE|" + row.get("instrument_id") + "|" + row.get("price_date");
            store.restate(key, row, ingestedAt);
            n++;
        }
        return n;
    }

    public static int loadPositions(Path csv, BitemporalStore store, Instant ingestedAt) throws IOException {
        int n = 0;
        for (Map<String, String> row : read(csv)) {
            String key = "POS|" + row.get("fund_id") + "|" + row.get("instrument_id") + "|" + row.get("position_date");
            store.restate(key, row, ingestedAt);
            n++;
        }
        return n;
    }

    public static List<Map<String, String>> read(Path csv) throws IOException {
        List<String> lines = Files.readAllLines(csv);
        List<Map<String, String>> out = new ArrayList<>();
        if (lines.isEmpty()) {
            return out;
        }
        String[] header = lines.get(0).split(",", -1);
        for (int i = 1; i < lines.size(); i++) {
            if (lines.get(i).isBlank()) {
                continue;
            }
            String[] cells = lines.get(i).split(",", -1);
            Map<String, String> row = new LinkedHashMap<>();
            for (int c = 0; c < header.length && c < cells.length; c++) {
                row.put(header[c], cells[c]);
            }
            out.add(row);
        }
        return out;
    }
}
