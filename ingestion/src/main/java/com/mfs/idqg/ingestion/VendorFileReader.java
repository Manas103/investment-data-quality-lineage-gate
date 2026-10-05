package com.mfs.idqg.ingestion;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Parses one vendor CSV file against a known declared schema, tolerant of schema drift (a
 * different actual column count or a missing declared column), which is exactly what
 * SCHEMA_DRIFT_COLUMN_COUNT and SCHEMA_DRIFT_MISSING_COLUMN are checking for. instrument_id,
 * as_of_date, vendor and schema_name are read positionally from whatever header the file
 * actually has (by name, if present), everything else defaults to blank if the file's header
 * doesn't carry it.
 */
public final class VendorFileReader {

    private VendorFileReader() {
    }

    public static List<IngestedRecord> read(Path file, VendorSchema declaredSchema) {
        try {
            List<String> lines = Files.readAllLines(file);
            if (lines.isEmpty()) {
                return List.of();
            }
            List<String> actualHeader = List.of(lines.get(0).split(",", -1));
            int declaredCount = declaredSchema.declaredColumns().size();
            boolean missingRequiredColumn = !actualHeader.containsAll(declaredSchema.requiredColumns());

            List<IngestedRecord> records = new ArrayList<>(lines.size() - 1);
            String fileName = file.getFileName().toString();
            for (int i = 1; i < lines.size(); i++) {
                String[] fields = lines.get(i).split(",", -1);
                Map<String, String> byName = new java.util.HashMap<>();
                for (int c = 0; c < actualHeader.size() && c < fields.length; c++) {
                    byName.put(actualHeader.get(c), fields[c]);
                }
                records.add(new IngestedRecord(
                        fileName, i, // sourceRow is 1-indexed, header excluded
                        byName.getOrDefault("vendor", ""),
                        byName.getOrDefault("schema_name", declaredSchema.name()),
                        byName.getOrDefault("instrument_id", ""),
                        byName.getOrDefault("as_of_date", ""),
                        byName.getOrDefault("received_at", ""),
                        byName.getOrDefault("currency", ""),
                        byName.getOrDefault("value1", ""),
                        byName.getOrDefault("value2", ""),
                        declaredCount,
                        actualHeader.size(),
                        missingRequiredColumn));
            }
            return records;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
