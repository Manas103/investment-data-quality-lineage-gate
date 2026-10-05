package com.mfs.idqg.ingestion;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Measures recall on the 500-defect corpus: for every seeded defect, is there a quarantine
 * violation naming the same check at the same (source file, source row)? Writes the full
 * accounting to docs/defect_benchmark_output.txt.
 */
class DefectCorpusRecallTest {

    @Test
    void measuresRecallAgainstThe500SeededDefects(@TempDir Path tempDir) throws IOException {
        LocalDate today = LocalDate.of(2026, 10, 5);
        SchemaRegistry registry = new SchemaRegistry();
        InstrumentMaster instruments = new InstrumentMaster(2000);
        VendorFileGenerator generator = new VendorFileGenerator(registry, instruments, today);

        Path csvDir = tempDir.resolve("csv");
        List<DefectManifestEntry> manifest = generator.generateDefectCorpus(csvDir, 6, 150, 1234L);
        assertEquals(500, manifest.size(), "the generator's own defect schedule must sum to exactly 500");

        IngestionPipeline pipeline = new IngestionPipeline(registry);
        Path vintageDir = tempDir.resolve("vintages");
        int filesIngested = pipeline.ingestDirectory(csvDir, vintageDir);

        List<IngestedRecord> records = IngestionPipeline.readBackAll(vintageDir);
        HealthCheckEngine engine = new HealthCheckEngine(registry, instruments, today);
        List<QuarantineViolation> violations = engine.runAll(records);

        Set<String> violationKeys = violations.stream()
                .map(v -> v.checkName() + "|" + v.sourceFile() + "|" + v.sourceRow())
                .collect(Collectors.toCollection(HashSet::new));

        int caught = 0;
        StringBuilder missedLog = new StringBuilder();
        for (DefectManifestEntry entry : manifest) {
            String key = entry.checkName() + "|" + entry.sourceFile() + "|" + entry.sourceRow();
            if (violationKeys.contains(key)) {
                caught++;
            } else {
                missedLog.append("MISSED ").append(key).append('\n');
            }
        }

        // Clean-row false positives within this same corpus: every record NOT at a manifest
        // coordinate, that still got quarantined.
        Set<String> manifestCoordinates = manifest.stream()
                .map(e -> e.sourceFile() + "|" + e.sourceRow())
                .collect(Collectors.toCollection(HashSet::new));
        // A DUPLICATE_ROW defect is recorded at the copy's position, but the health check
        // correctly also quarantines the original row immediately before it (the two rows are
        // identical; there is no way to single out "the" mistake), so that companion position is
        // not a false positive either.
        for (DefectManifestEntry entry : manifest) {
            if (entry.checkName().equals(HealthChecks.DUPLICATE_ROW)) {
                manifestCoordinates.add(entry.sourceFile() + "|" + (entry.sourceRow() - 1));
            }
        }
        List<QuarantineViolation> falsePositives = violations.stream()
                .filter(v -> !manifestCoordinates.contains(v.sourceFile() + "|" + v.sourceRow()))
                .toList();
        long falseQuarantinesInDefectCorpus = falsePositives.stream()
                .map(v -> v.sourceFile() + "|" + v.sourceRow())
                .distinct()
                .count();

        String report = "Vendor ingestion defect-corpus recall benchmark\n"
                + "Files ingested: " + filesIngested + "\n"
                + "Records loaded back from Parquet: " + records.size() + "\n"
                + "Seeded defects: " + manifest.size() + "\n"
                + "Caught: " + caught + " of " + manifest.size() + "\n"
                + "Total quarantine violations raised: " + violations.size() + " (a defect can legitimately trip more than one check)\n"
                + "False-quarantined clean records in this corpus: " + falseQuarantinesInDefectCorpus + "\n\n"
                + missedLog;

        Path docsDir = Path.of("docs");
        Files.createDirectories(docsDir);
        Files.writeString(docsDir.resolve("defect_benchmark_output.txt"), report, StandardCharsets.UTF_8);
        System.out.println(report);

        assertTrue(caught >= 400, "expected most of the 500 seeded defects to be caught, got " + caught);
    }
}
