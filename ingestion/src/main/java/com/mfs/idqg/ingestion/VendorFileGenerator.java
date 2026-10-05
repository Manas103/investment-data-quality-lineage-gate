package com.mfs.idqg.ingestion;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;

/**
 * Writes real vendor feed files (plain CSV text, one header line then data rows, no embedded
 * commas, same simplification precedent the sibling investment-data-quality generator uses) to
 * disk. Two corpora are produced: a large, fully clean corpus (the 1.2M-record false-quarantine
 * claim) and a smaller corpus with exactly 500 defects seeded in place, at the moment each row or
 * file is written, never by a post-pass that could shift anything already recorded (the sibling
 * project's generator had exactly that bug; see this repo's README Findings for both. This
 * generator avoids the whole bug class by construction).
 */
public final class VendorFileGenerator {

    private static final String[] VENDORS = {
            "VENDOR_ALPHA", "VENDOR_BETA", "VENDOR_GAMMA", "VENDOR_DELTA",
            "VENDOR_EPSILON", "VENDOR_ZETA"};

    private final SchemaRegistry schemaRegistry;
    private final InstrumentMaster instrumentMaster;
    private final LocalDate today;

    public VendorFileGenerator(SchemaRegistry schemaRegistry, InstrumentMaster instrumentMaster, LocalDate today) {
        this.schemaRegistry = schemaRegistry;
        this.instrumentMaster = instrumentMaster;
        this.today = today;
    }

    /** Generates filesPerSchema * 40 files of rowsPerFile rows each, every row valid and unique. No defects. */
    public int generateCleanCorpus(Path outDir, int filesPerSchema, int rowsPerFile, long seed) {
        Random random = new Random(seed);
        int totalRows = 0;
        int instrumentPoolSize = instrumentMaster.instrumentIds().size();
        List<VendorSchema> schemas = schemaRegistry.all();
        for (VendorSchema schema : schemas) {
            for (int f = 0; f < filesPerSchema; f++) {
                String vendor = VENDORS[f % VENDORS.length];
                List<List<String>> rows = new ArrayList<>(rowsPerFile);
                int instrumentOffset = (f * rowsPerFile) % instrumentPoolSize;
                for (int r = 0; r < rowsPerFile; r++) {
                    String instrumentId = instrumentMaster.instrumentIds().get((instrumentOffset + r) % instrumentPoolSize);
                    rows.add(buildValidRow(schema, vendor, instrumentId, today.minusDays(1), today, random));
                }
                String fileName = String.format("%s_%s_%s_%03d.csv", vendor, schema.name(), today, f);
                writeFile(outDir.resolve(fileName), schema.declaredColumns(), rows);
                totalRows += rows.size();
            }
        }
        return totalRows;
    }

    /**
     * Generates the defect corpus: a main pass of normal files hosting 360 single-row defects
     * (36 per check, across 10 row-level checks) plus dedicated files for the 4 file-shaped
     * checks (schema drift x2, row-count anomaly, cross-source disagreement), summing to exactly
     * 500. Returns the manifest of exactly where each one was planted.
     */
    public List<DefectManifestEntry> generateDefectCorpus(Path outDir, int filesPerSchemaMain, int rowsPerFileMain, long seed) {
        Random random = new Random(seed);
        List<DefectManifestEntry> manifest = new ArrayList<>();
        List<VendorSchema> schemas = schemaRegistry.all();
        int instrumentPoolSize = instrumentMaster.instrumentIds().size();

        List<String> rowLevelChecks = List.of(
                HealthChecks.STALE_FEED, HealthChecks.MISSING_REQUIRED_FIELD, HealthChecks.VALUE_OUT_OF_RANGE,
                HealthChecks.DUPLICATE_ROW, HealthChecks.NEGATIVE_VALUE, HealthChecks.FUTURE_DATED_RECORD,
                HealthChecks.INVALID_CURRENCY_CODE, HealthChecks.LATE_ARRIVING_RECORD,
                HealthChecks.ORPHAN_INSTRUMENT_REFERENCE, HealthChecks.TYPE_MISMATCH);
        int perCheck = 36;
        List<String> schedule = new ArrayList<>();
        for (String check : rowLevelChecks) {
            for (int i = 0; i < perCheck; i++) {
                schedule.add(check);
            }
        }
        Collections.shuffle(schedule, random);
        int scheduleIndex = 0;

        for (VendorSchema schema : schemas) {
            for (int f = 0; f < filesPerSchemaMain; f++) {
                String vendor = VENDORS[f % VENDORS.length];
                List<List<String>> rows = new ArrayList<>();
                int instrumentOffset = (f * rowsPerFileMain) % instrumentPoolSize;
                for (int r = 0; r < rowsPerFileMain; r++) {
                    String instrumentId = instrumentMaster.instrumentIds().get((instrumentOffset + r) % instrumentPoolSize);
                    List<String> row = buildValidRow(schema, vendor, instrumentId, today.minusDays(1), today, random);
                    rows.add(row);

                    // 1-in-6 chance per row to consume the next scheduled defect, so defects land
                    // at varied row positions rather than all piling up at the start of each file.
                    if (scheduleIndex < schedule.size() && random.nextInt(6) == 0) {
                        String check = schedule.get(scheduleIndex);
                        applyRowLevelDefect(check, schema, row, rows);
                        scheduleIndex++;
                        // Computed after the defect is applied: DUPLICATE_ROW appends a new row
                        // immediately after the original it copies (the defect instance is
                        // recorded at the copy's position; the health check correctly quarantines
                        // the original too, since the two are indistinguishable, which is a
                        // disclosed companion effect, not a separate defect). Every other check
                        // mutates `row` in place and leaves the row count unchanged, so
                        // rows.size() still names that row's own 1-indexed position either way.
                        manifest.add(new DefectManifestEntry(check, "", rows.size()));
                    }
                }
                boolean isLastFile = schema.equals(schemas.get(schemas.size() - 1)) && f == filesPerSchemaMain - 1;
                if (isLastFile) {
                    // Flush any scheduled defects that didn't get a random slot in time.
                    while (scheduleIndex < schedule.size()) {
                        String check = schedule.get(scheduleIndex);
                        String instrumentId = instrumentMaster.instrumentIds().get(scheduleIndex % instrumentPoolSize);
                        List<String> row = buildValidRow(schema, vendor, instrumentId, today.minusDays(1), today, random);
                        rows.add(row);
                        applyRowLevelDefect(check, schema, row, rows);
                        manifest.add(new DefectManifestEntry(check, "", rows.size()));
                        scheduleIndex++;
                    }
                }
                String fileName = String.format("%s_%s_%s_defectmain.csv", vendor, schema.name(), today);
                writeFile(outDir.resolve(fileName), schema.declaredColumns(), rows);
                // Manifest entries for this file were just appended with a blank file name, in
                // row order; back-fill them now that the file name is known.
                backfillFileNameForTrailingEntries(manifest, fileName);
            }
        }

        manifest.addAll(generateSchemaDriftColumnCountFiles(outDir, schemas.get(0), random));
        manifest.addAll(generateSchemaDriftMissingColumnFiles(outDir, schemas.get(1), random));
        manifest.addAll(generateRowCountAnomalyFiles(outDir, schemas.get(2), random));
        manifest.addAll(generateCrossSourceDisagreementFiles(outDir, schemas.get(3), random));

        return manifest;
    }

    private void backfillFileNameForTrailingEntries(List<DefectManifestEntry> manifest, String fileName) {
        for (int i = manifest.size() - 1; i >= 0; i--) {
            DefectManifestEntry e = manifest.get(i);
            if (!e.sourceFile().isEmpty()) {
                break;
            }
            manifest.set(i, new DefectManifestEntry(e.checkName(), fileName, e.sourceRow()));
        }
    }

    private void applyRowLevelDefect(String check, VendorSchema schema, List<String> row, List<List<String>> rowsSoFar) {
        List<String> columns = schema.declaredColumns();
        switch (check) {
            case HealthChecks.STALE_FEED -> setField(row, columns, "as_of_date", today.minusDays(10).toString());
            case HealthChecks.MISSING_REQUIRED_FIELD -> setField(row, columns, "instrument_id", "");
            case HealthChecks.VALUE_OUT_OF_RANGE -> setField(row, columns, "value1", Double.toString(schema.value1UpperBound() * 5));
            case HealthChecks.NEGATIVE_VALUE -> setField(row, columns, "value1", "-100.0");
            case HealthChecks.FUTURE_DATED_RECORD -> setField(row, columns, "as_of_date", today.plusDays(5).toString());
            case HealthChecks.INVALID_CURRENCY_CODE -> setField(row, columns, "currency", "ZZZ");
            case HealthChecks.LATE_ARRIVING_RECORD -> setField(row, columns, "as_of_date", today.minusDays(8).toString());
            case HealthChecks.ORPHAN_INSTRUMENT_REFERENCE -> setField(row, columns, "instrument_id", "INST-99999");
            case HealthChecks.TYPE_MISMATCH -> setField(row, columns, "value1", "NOT_A_NUMBER");
            case HealthChecks.DUPLICATE_ROW -> rowsSoFar.add(new ArrayList<>(row)); // the duplicate itself is the defect
            default -> throw new IllegalStateException("unhandled row-level check: " + check);
        }
    }

    private List<DefectManifestEntry> generateSchemaDriftColumnCountFiles(Path outDir, VendorSchema schema, Random random) {
        List<DefectManifestEntry> entries = new ArrayList<>();
        List<String> actualColumns = new ArrayList<>(schema.declaredColumns());
        actualColumns.add("unexpected_extra_column"); // drift: one more column than declared
        for (int fileNum = 0; fileNum < 4; fileNum++) {
            String vendor = VENDORS[fileNum % VENDORS.length];
            List<List<String>> rows = new ArrayList<>();
            for (int r = 0; r < 10; r++) {
                List<String> base = buildValidRow(schema, vendor, instrumentMaster.instrumentIds().get(1900 + fileNum * 10 + r), today.minusDays(1), today, random);
                List<String> withExtra = new ArrayList<>(base);
                withExtra.add("extra-" + r);
                rows.add(withExtra);
            }
            String fileName = String.format("%s_%s_%s_drift_colcount_%d.csv", vendor, schema.name(), today, fileNum);
            writeFile(outDir.resolve(fileName), actualColumns, rows);
            for (int r = 1; r <= rows.size(); r++) {
                entries.add(new DefectManifestEntry(HealthChecks.SCHEMA_DRIFT_COLUMN_COUNT, fileName, r));
            }
        }
        return entries;
    }

    private List<DefectManifestEntry> generateSchemaDriftMissingColumnFiles(Path outDir, VendorSchema schema, Random random) {
        List<DefectManifestEntry> entries = new ArrayList<>();
        List<String> actualColumns = new ArrayList<>(schema.declaredColumns());
        int idx = actualColumns.indexOf("as_of_date");
        actualColumns.remove(idx); // drift: a required declared column is simply absent
        for (int fileNum = 0; fileNum < 4; fileNum++) {
            String vendor = VENDORS[fileNum % VENDORS.length];
            List<List<String>> rows = new ArrayList<>();
            for (int r = 0; r < 10; r++) {
                List<String> base = buildValidRow(schema, vendor, instrumentMaster.instrumentIds().get(1900 + fileNum * 10 + r), today.minusDays(1), today, random);
                List<String> withoutColumn = new ArrayList<>(base);
                withoutColumn.remove(idx);
                rows.add(withoutColumn);
            }
            String fileName = String.format("%s_%s_%s_drift_missingcol_%d.csv", vendor, schema.name(), today, fileNum);
            writeFile(outDir.resolve(fileName), actualColumns, rows);
            for (int r = 1; r <= rows.size(); r++) {
                entries.add(new DefectManifestEntry(HealthChecks.SCHEMA_DRIFT_MISSING_COLUMN, fileName, r));
            }
        }
        return entries;
    }

    private List<DefectManifestEntry> generateRowCountAnomalyFiles(Path outDir, VendorSchema schema, Random random) {
        List<DefectManifestEntry> entries = new ArrayList<>();
        for (int fileNum = 0; fileNum < 2; fileNum++) {
            String vendor = VENDORS[fileNum % VENDORS.length];
            List<List<String>> rows = new ArrayList<>();
            for (int r = 0; r < 10; r++) { // deliberately far below the schema's normal file size
                rows.add(buildValidRow(schema, vendor, instrumentMaster.instrumentIds().get(1900 + fileNum * 10 + r), today.minusDays(1), today, random));
            }
            String fileName = String.format("%s_%s_%s_rowcount_anomaly_%d.csv", vendor, schema.name(), today, fileNum);
            writeFile(outDir.resolve(fileName), schema.declaredColumns(), rows);
            for (int r = 1; r <= rows.size(); r++) {
                entries.add(new DefectManifestEntry(HealthChecks.ROW_COUNT_ANOMALY, fileName, r));
            }
        }
        return entries;
    }

    private List<DefectManifestEntry> generateCrossSourceDisagreementFiles(Path outDir, VendorSchema schema, Random random) {
        List<DefectManifestEntry> entries = new ArrayList<>();
        for (int pairNum = 0; pairNum < 20; pairNum++) {
            String instrumentId = instrumentMaster.instrumentIds().get(1000 + pairNum);
            double baseValue = 100.0 + pairNum;
            double disagreeingValue = baseValue * 1.5; // > 15% disagreement threshold

            List<String> rowA = buildValidRow(schema, "VENDOR_ALPHA", instrumentId, today.minusDays(1), today, random);
            setField(rowA, schema.declaredColumns(), "value1", Double.toString(baseValue));
            String fileA = String.format("VENDOR_ALPHA_%s_%s_disagree_%d_a.csv", schema.name(), today, pairNum);
            writeFile(outDir.resolve(fileA), schema.declaredColumns(), List.of(rowA));
            entries.add(new DefectManifestEntry(HealthChecks.CROSS_SOURCE_DISAGREEMENT, fileA, 1));

            List<String> rowB = buildValidRow(schema, "VENDOR_BETA", instrumentId, today.minusDays(1), today, random);
            setField(rowB, schema.declaredColumns(), "value1", Double.toString(disagreeingValue));
            String fileB = String.format("VENDOR_BETA_%s_%s_disagree_%d_b.csv", schema.name(), today, pairNum);
            writeFile(outDir.resolve(fileB), schema.declaredColumns(), List.of(rowB));
            entries.add(new DefectManifestEntry(HealthChecks.CROSS_SOURCE_DISAGREEMENT, fileB, 1));
        }
        return entries;
    }

    private List<String> buildValidRow(VendorSchema schema, String vendor, String instrumentId,
                                        LocalDate asOfDate, LocalDate receivedAt, Random random) {
        String currency = InstrumentMaster.currencyFor(random);
        String region = InstrumentMaster.regionFor(random);
        double value1 = round2(random.nextDouble() * schema.value1UpperBound() * 0.8 + 1.0);
        double value2 = round2(random.nextDouble() * schema.value1UpperBound() * 0.8 + 1.0);
        List<String> values = new ArrayList<>();
        for (String col : schema.declaredColumns()) {
            String v = switch (col) {
                case "instrument_id" -> instrumentId;
                case "as_of_date" -> asOfDate.toString();
                case "received_at" -> receivedAt.toString();
                case "currency" -> currency;
                case "value1" -> Double.toString(value1);
                case "value2" -> Double.toString(value2);
                case "status" -> "ACTIVE";
                case "region" -> region;
                case "vendor" -> vendor;
                case "schema_name" -> schema.name();
                default -> "";
            };
            values.add(v);
        }
        return values;
    }

    private static double round2(double d) {
        return Math.round(d * 100.0) / 100.0;
    }

    private static void setField(List<String> row, List<String> columns, String fieldName, String value) {
        int idx = columns.indexOf(fieldName);
        if (idx >= 0 && idx < row.size()) {
            row.set(idx, value);
        }
    }

    private static void writeFile(Path path, List<String> header, List<List<String>> rows) {
        try {
            Files.createDirectories(path.getParent());
            StringBuilder sb = new StringBuilder();
            sb.append(String.join(",", header)).append('\n');
            for (List<String> row : rows) {
                sb.append(String.join(",", row)).append('\n');
            }
            Files.writeString(path, sb.toString(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
