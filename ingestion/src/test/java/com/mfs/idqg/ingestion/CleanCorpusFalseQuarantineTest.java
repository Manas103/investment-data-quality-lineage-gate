package com.mfs.idqg.ingestion;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * The scale claim: 1,200+ simulated vendor files/day across 40 schemas, 0 false quarantines over
 * 1.2M records. 40 schemas x 30 files/schema x 1,000 rows/file = 1,200 files, 1,200,000 rows,
 * every one of them valid. Raw output: docs/clean_benchmark_output.txt.
 */
class CleanCorpusFalseQuarantineTest {

    @Test
    void zeroFalseQuarantinesOverOnePointTwoMillionCleanRecords(@TempDir Path tempDir) throws IOException {
        LocalDate today = LocalDate.of(2026, 10, 5);
        SchemaRegistry registry = new SchemaRegistry();
        // Large enough that generateCleanCorpus's per-file instrument window (fileIndex *
        // rowsPerFile, 30 files x 1,000 rows/file = 29,000 at most) never wraps back over an
        // earlier file's window; a wrap would make two files share (instrument, as-of-date) under
        // different vendors and spuriously trip CROSS_SOURCE_DISAGREEMENT. This was the first
        // measurement here, caught by this exact symptom; see README Findings.
        InstrumentMaster instruments = new InstrumentMaster(35_000);
        VendorFileGenerator generator = new VendorFileGenerator(registry, instruments, today);

        Path csvDir = tempDir.resolve("csv");
        long genStart = System.nanoTime();
        int totalRowsGenerated = generator.generateCleanCorpus(csvDir, 30, 1000, 99L);
        double genSeconds = (System.nanoTime() - genStart) / 1_000_000_000.0;
        assertEquals(1_200_000, totalRowsGenerated);

        IngestionPipeline pipeline = new IngestionPipeline(registry);
        Path vintageDir = tempDir.resolve("vintages");
        long ingestStart = System.nanoTime();
        int filesIngested = pipeline.ingestDirectory(csvDir, vintageDir);
        double ingestSeconds = (System.nanoTime() - ingestStart) / 1_000_000_000.0;
        assertEquals(1200, filesIngested);

        long readStart = System.nanoTime();
        List<IngestedRecord> records = IngestionPipeline.readBackAll(vintageDir);
        double readSeconds = (System.nanoTime() - readStart) / 1_000_000_000.0;
        assertEquals(1_200_000, records.size());

        HealthCheckEngine engine = new HealthCheckEngine(registry, instruments, today);
        long checkStart = System.nanoTime();
        List<QuarantineViolation> violations = engine.runAll(records);
        double checkSeconds = (System.nanoTime() - checkStart) / 1_000_000_000.0;

        String report = String.format(
                "Vendor ingestion clean-corpus false-quarantine benchmark%n"
                + "Machine: 8 physical / 16 logical cores, AMD Ryzen 7 7800X3D, Windows 11 Home, JDK 21 Temurin%n"
                + "1,200 files across 40 schemas (30 files/schema), 1,000 rows/file = 1,200,000 records%n"
                + "Generation (CSV write): %.2fs%n"
                + "Ingestion (parse + Parquet write): %.2fs%n"
                + "Read-back (Parquet -> IngestedRecord, all 1.2M rows): %.2fs%n"
                + "14 health checks over all 1.2M records: %.2fs%n"
                + "False quarantines: %d of %d records (claim: 0)%n",
                genSeconds, ingestSeconds, readSeconds, checkSeconds, violations.size(), records.size());

        Path docsDir = Path.of("docs");
        Files.createDirectories(docsDir);
        Files.writeString(docsDir.resolve("clean_benchmark_output.txt"), report, StandardCharsets.UTF_8);
        System.out.println(report);

        assertEquals(0, violations.size(), "a clean corpus must raise exactly zero quarantine violations");
    }
}
