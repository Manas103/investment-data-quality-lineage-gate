package com.mfs.idqg.ingestion;

import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Runs all 14 named SQL-shaped health checks over a fully loaded corpus of IngestedRecord (read
 * back from Parquet, not the original CSVs: HealthCheckEngineSqlTest proves these same checks
 * expressed as literal SQL against an H2 table agree with this Java pass). Every check that
 * fires produces one QuarantineViolation carrying the exact source file and row.
 */
public final class HealthCheckEngine {

    private static final double STALE_FEED_LAG_DAYS = 5;
    private static final double LATE_ARRIVING_LAG_DAYS = 6;
    private static final double CROSS_SOURCE_DISAGREEMENT_THRESHOLD = 0.15;
    private static final double ROW_COUNT_ANOMALY_RATIO = 0.3;

    private final Map<String, VendorSchema> schemasByName;
    private final InstrumentMaster instrumentMaster;
    private final LocalDate today;

    public HealthCheckEngine(SchemaRegistry registry, InstrumentMaster instrumentMaster, LocalDate today) {
        this.schemasByName = registry.all().stream()
                .collect(java.util.stream.Collectors.toMap(VendorSchema::name, s -> s));
        this.instrumentMaster = instrumentMaster;
        this.today = today;
    }

    public List<QuarantineViolation> runAll(List<IngestedRecord> records) {
        List<QuarantineViolation> violations = new ArrayList<>();

        // Precompute per-file row counts (for ROW_COUNT_ANOMALY) and the corpus-wide average per schema.
        Map<String, Integer> rowsPerFile = new HashMap<>();
        for (IngestedRecord r : records) {
            rowsPerFile.merge(r.sourceFile, 1, Integer::sum);
        }

        // Group by (schemaName, instrumentId, asOfDate) for cross-source disagreement, and by
        // (vendor, schemaName, instrumentId, asOfDate) for duplicate-row detection.
        Map<String, List<IngestedRecord>> byInstrumentDate = new HashMap<>();
        Map<String, Integer> dupKeyCount = new HashMap<>();
        for (IngestedRecord r : records) {
            byInstrumentDate.computeIfAbsent(r.schemaName + "|" + r.instrumentId + "|" + r.asOfDateRaw, k -> new ArrayList<>()).add(r);
            String dupKey = r.vendor + "|" + r.schemaName + "|" + r.instrumentId + "|" + r.asOfDateRaw;
            dupKeyCount.merge(dupKey, 1, Integer::sum);
        }

        // Average row count per schema, used by ROW_COUNT_ANOMALY; computed from per-file counts
        // resolved via the first record of each file (every record in a file shares schemaName).
        Map<String, String> schemaByFile = new HashMap<>();
        for (IngestedRecord r : records) {
            schemaByFile.putIfAbsent(r.sourceFile, r.schemaName);
        }
        // The baseline population deliberately excludes very small files (under 50 rows): this
        // corpus's own dedicated schema-drift and cross-source-disagreement fixture files are
        // tiny by construction (10 rows, or even 1), and including them would drag a schema's
        // baseline down to the point that every genuinely normal-sized file looked anomalous by
        // comparison, a real bug this project's own first measurement hit (see README Findings).
        // Every file, including the tiny ones, is still evaluated against the resulting baseline.
        Map<String, List<Integer>> rowCountsBySchema = new HashMap<>();
        for (Map.Entry<String, Integer> e : rowsPerFile.entrySet()) {
            if (e.getValue() < 50) {
                continue;
            }
            String schema = schemaByFile.get(e.getKey());
            rowCountsBySchema.computeIfAbsent(schema, k -> new ArrayList<>()).add(e.getValue());
        }
        Map<String, Double> baselineRowsBySchema = new HashMap<>();
        for (Map.Entry<String, List<Integer>> e : rowCountsBySchema.entrySet()) {
            List<Integer> sorted = new ArrayList<>(e.getValue());
            sorted.sort(null);
            int mid = sorted.size() / 2;
            double median = sorted.size() % 2 == 1 ? sorted.get(mid) : (sorted.get(mid - 1) + sorted.get(mid)) / 2.0;
            baselineRowsBySchema.put(e.getKey(), median);
        }

        Set<String> flaggedCrossSourcePairs = new HashSet<>();

        for (IngestedRecord r : records) {
            VendorSchema schema = schemasByName.get(r.schemaName);

            // SCHEMA_DRIFT_COLUMN_COUNT: this record's file had a different actual column count than declared.
            if (r.actualColumnCount != r.declaredColumnCount) {
                violations.add(new QuarantineViolation(HealthChecks.SCHEMA_DRIFT_COLUMN_COUNT, r.sourceFile, r.sourceRow,
                        "actual column count " + r.actualColumnCount + " != declared " + r.declaredColumnCount));
            }
            // SCHEMA_DRIFT_MISSING_COLUMN
            if (r.missingRequiredColumn) {
                violations.add(new QuarantineViolation(HealthChecks.SCHEMA_DRIFT_MISSING_COLUMN, r.sourceFile, r.sourceRow,
                        "file header is missing a required declared column"));
            }
            // MISSING_REQUIRED_FIELD
            if (isBlank(r.instrumentId) || isBlank(r.asOfDateRaw)) {
                violations.add(new QuarantineViolation(HealthChecks.MISSING_REQUIRED_FIELD, r.sourceFile, r.sourceRow,
                        "instrument_id or as_of_date is blank"));
            }
            // ORPHAN_INSTRUMENT_REFERENCE
            if (!isBlank(r.instrumentId) && !instrumentMaster.isValid(r.instrumentId)) {
                violations.add(new QuarantineViolation(HealthChecks.ORPHAN_INSTRUMENT_REFERENCE, r.sourceFile, r.sourceRow,
                        "instrument_id " + r.instrumentId + " not present in the reference instrument master"));
            }
            // INVALID_CURRENCY_CODE
            if (!isBlank(r.currency) && !InstrumentMaster.isKnownCurrency(r.currency)) {
                violations.add(new QuarantineViolation(HealthChecks.INVALID_CURRENCY_CODE, r.sourceFile, r.sourceRow,
                        "currency " + r.currency + " is not a recognized ISO code"));
            }

            LocalDate asOfDate = tryParseDate(r.asOfDateRaw);
            LocalDate receivedAt = tryParseDate(r.receivedAtRaw);
            if (asOfDate != null) {
                if (asOfDate.isAfter(today)) {
                    violations.add(new QuarantineViolation(HealthChecks.FUTURE_DATED_RECORD, r.sourceFile, r.sourceRow,
                            "as_of_date " + asOfDate + " is after today " + today));
                }
                long lagFromToday = java.time.temporal.ChronoUnit.DAYS.between(asOfDate, today);
                if (lagFromToday > STALE_FEED_LAG_DAYS) {
                    violations.add(new QuarantineViolation(HealthChecks.STALE_FEED, r.sourceFile, r.sourceRow,
                            "as_of_date " + asOfDate + " is " + lagFromToday + " days behind today, beyond the staleness threshold"));
                }
                if (lagFromToday > LATE_ARRIVING_LAG_DAYS) {
                    violations.add(new QuarantineViolation(HealthChecks.LATE_ARRIVING_RECORD, r.sourceFile, r.sourceRow,
                            "as_of_date " + asOfDate + " arrived " + lagFromToday + " days late"));
                }
            }

            Double value1 = tryParseDouble(r.value1Raw);
            if (r.value1Raw != null && !r.value1Raw.isBlank() && value1 == null) {
                violations.add(new QuarantineViolation(HealthChecks.TYPE_MISMATCH, r.sourceFile, r.sourceRow,
                        "value1 '" + r.value1Raw + "' is not numeric"));
            }
            if (value1 != null && schema != null) {
                if (value1 < 0 && !schema.allowNegativeValue1()) {
                    violations.add(new QuarantineViolation(HealthChecks.NEGATIVE_VALUE, r.sourceFile, r.sourceRow,
                            "value1 " + value1 + " is negative"));
                }
                if (Math.abs(value1) > schema.value1UpperBound()) {
                    violations.add(new QuarantineViolation(HealthChecks.VALUE_OUT_OF_RANGE, r.sourceFile, r.sourceRow,
                            "value1 " + value1 + " exceeds schema bound " + schema.value1UpperBound()));
                }
            }

            String dupKey = r.vendor + "|" + r.schemaName + "|" + r.instrumentId + "|" + r.asOfDateRaw;
            if (dupKeyCount.getOrDefault(dupKey, 0) > 1) {
                violations.add(new QuarantineViolation(HealthChecks.DUPLICATE_ROW, r.sourceFile, r.sourceRow,
                        "(vendor, schema, instrument, as_of_date) appears more than once in this vintage"));
            }

            Double baseline = baselineRowsBySchema.get(r.schemaName);
            Integer thisFileRows = rowsPerFile.get(r.sourceFile);
            if (baseline != null && baseline > 0 && thisFileRows != null
                    && Math.abs(thisFileRows - baseline) / baseline > ROW_COUNT_ANOMALY_RATIO) {
                violations.add(new QuarantineViolation(HealthChecks.ROW_COUNT_ANOMALY, r.sourceFile, r.sourceRow,
                        "file has " + thisFileRows + " rows, schema median is " + baseline));
            }

            // CROSS_SOURCE_DISAGREEMENT: compare against every other vendor reporting the same
            // (schema, instrument, as_of_date); flag every record in a disagreeing group once.
            String groupKey = r.schemaName + "|" + r.instrumentId + "|" + r.asOfDateRaw;
            if (!flaggedCrossSourcePairs.contains(groupKey) && value1 != null) {
                List<IngestedRecord> group = byInstrumentDate.get(groupKey);
                if (group != null && group.size() > 1 && disagreesAcrossVendors(group)) {
                    flaggedCrossSourcePairs.add(groupKey);
                    for (IngestedRecord member : group) {
                        violations.add(new QuarantineViolation(HealthChecks.CROSS_SOURCE_DISAGREEMENT,
                                member.sourceFile, member.sourceRow,
                                "value1 disagrees by more than " + (int) (CROSS_SOURCE_DISAGREEMENT_THRESHOLD * 100)
                                        + "% across vendors for the same instrument/date"));
                    }
                }
            }
        }

        return violations;
    }

    private boolean disagreesAcrossVendors(List<IngestedRecord> group) {
        Map<String, Double> byVendor = new HashMap<>();
        for (IngestedRecord r : group) {
            Double v = tryParseDouble(r.value1Raw);
            if (v != null) {
                byVendor.put(r.vendor, v);
            }
        }
        if (byVendor.size() < 2) {
            return false;
        }
        double max = byVendor.values().stream().mapToDouble(Double::doubleValue).max().orElse(0);
        double min = byVendor.values().stream().mapToDouble(Double::doubleValue).min().orElse(0);
        if (max == 0) {
            return false;
        }
        return (max - min) / max > CROSS_SOURCE_DISAGREEMENT_THRESHOLD;
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }

    private static LocalDate tryParseDate(String s) {
        if (isBlank(s)) {
            return null;
        }
        try {
            return LocalDate.parse(s);
        } catch (DateTimeParseException e) {
            return null;
        }
    }

    private static Double tryParseDouble(String s) {
        if (isBlank(s)) {
            return null;
        }
        try {
            return Double.parseDouble(s);
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
