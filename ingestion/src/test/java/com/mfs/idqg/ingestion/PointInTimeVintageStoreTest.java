package com.mfs.idqg.ingestion;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Proves the Parquet round-trip and the point-in-time restatement claim directly, independent of the big corpora. */
class PointInTimeVintageStoreTest {

    @Test
    void writeThenReadReturnsExactFieldsBackForEveryRecord(@TempDir Path tempDir) {
        SchemaRegistry registry = new SchemaRegistry();
        VendorSchema schema = registry.all().get(0);
        InstrumentMaster instruments = new InstrumentMaster(10);
        VendorFileGenerator generator = new VendorFileGenerator(registry, instruments, LocalDate.of(2026, 10, 5));

        Path csvDir = tempDir.resolve("csv");
        generator.generateCleanCorpus(csvDir, 1, 20, 7);

        IngestionPipeline pipeline = new IngestionPipeline(registry);
        Path vintageDir = tempDir.resolve("vintages");
        int ingested = pipeline.ingestDirectory(csvDir, vintageDir);
        assertTrue(ingested >= 1);

        List<IngestedRecord> readBack = IngestionPipeline.readBackAll(vintageDir);
        assertEquals(40 * 20, readBack.size()); // 1 file per schema across all 40 schemas, 20 rows each
        for (IngestedRecord r : readBack) {
            assertTrue(r.instrumentId.startsWith("INST-"));
            assertEquals(schema.declaredColumns().size(), r.declaredColumnCount);
        }
    }

    @Test
    void reingestingTheSameAsOfDateUnderANewSourceFileKeepsBothVintagesReadableExactly(@TempDir Path tempDir) {
        SchemaRegistry registry = new SchemaRegistry();
        VendorSchema schema = registry.all().get(0);
        InstrumentMaster instruments = new InstrumentMaster(5);
        VendorFileGenerator generator = new VendorFileGenerator(registry, instruments, LocalDate.of(2026, 10, 5));

        Path csvDir = tempDir.resolve("csv");
        // Two independent "deliveries" for the same schema and as-of-date: a restatement.
        generator.generateCleanCorpus(csvDir, 1, 5, 1);
        // The second delivery lands in its own directory so its generated file name (which
        // encodes only vendor/schema/date, not a delivery number) cannot collide with the first.
        Path csvDir2 = tempDir.resolve("csv2");
        generator.generateCleanCorpus(csvDir2, 1, 5, 2);

        IngestionPipeline pipeline = new IngestionPipeline(registry);
        Path vintageDir = tempDir.resolve("vintages");
        pipeline.ingestDirectory(csvDir, vintageDir);
        pipeline.ingestDirectory(csvDir2, vintageDir);

        List<IngestedRecord> readBack = IngestionPipeline.readBackAll(vintageDir);
        long forThisSchema = readBack.stream().filter(r -> r.schemaName.equals(schema.name())).count();
        // 5 rows from each of two independent deliveries for this one schema; neither overwrote the other.
        assertEquals(10, forThisSchema);
        long vintageFileCountForThisSchema;
        try (var walk = java.nio.file.Files.walk(vintageDir.resolve(schema.name()))) {
            vintageFileCountForThisSchema = walk.filter(p -> p.toString().endsWith(".parquet")).count();
        } catch (java.io.IOException e) {
            throw new java.io.UncheckedIOException(e);
        }
        assertEquals(2, vintageFileCountForThisSchema, "both vintages must exist on disk as separate files, neither overwritten");
    }
}
