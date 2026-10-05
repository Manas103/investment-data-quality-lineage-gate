package com.mfs.idqg.ingestion;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Stream;

/**
 * Ties the pieces together: for every vendor CSV file in a directory, resolves which of the 40
 * declared schemas it claims to be (by reading the schema_name value out of its own first data
 * row, tolerant of the file's actual header otherwise), parses it (VendorFileReader), and writes
 * it as one immutable Parquet vintage (PointInTimeVintageStore) under
 * vintages/&lt;schema&gt;/&lt;source-file-stem&gt;-&lt;uuid&gt;.parquet. Re-ingesting the same
 * (schema, as-of-date) under a different source file name a second time, as a restatement would,
 * produces a second vintage file alongside the first; neither is ever overwritten.
 */
public final class IngestionPipeline {

    private final Map<String, VendorSchema> schemasByName;

    public IngestionPipeline(SchemaRegistry registry) {
        this.schemasByName = registry.all().stream()
                .collect(java.util.stream.Collectors.toMap(VendorSchema::name, s -> s));
    }

    /** Ingests every *.csv file directly under csvDir, writing one Parquet vintage per file under vintageDir. Returns the number of files ingested. */
    public int ingestDirectory(Path csvDir, Path vintageDir) {
        List<Path> files;
        try (Stream<Path> walk = Files.list(csvDir)) {
            files = walk.filter(p -> p.toString().endsWith(".csv")).sorted(Comparator.naturalOrder()).toList();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        for (Path file : files) {
            ingestOneFile(file, vintageDir);
        }
        return files.size();
    }

    private void ingestOneFile(Path csvFile, Path vintageDir) {
        String schemaName = resolveSchemaName(csvFile);
        VendorSchema schema = schemasByName.get(schemaName);
        if (schema == null) {
            throw new IllegalStateException("unresolvable schema_name in " + csvFile + ": " + schemaName);
        }
        List<IngestedRecord> records = VendorFileReader.read(csvFile, schema);
        String stem = csvFile.getFileName().toString().replace(".csv", "");
        Path vintageFile = vintageDir.resolve(schemaName).resolve(stem + "-" + UUID.randomUUID() + ".parquet");
        PointInTimeVintageStore.writeVintage(vintageFile, records);
    }

    private String resolveSchemaName(Path csvFile) {
        try {
            List<String> lines = Files.readAllLines(csvFile);
            if (lines.size() < 2) {
                throw new IllegalStateException("empty vendor file: " + csvFile);
            }
            List<String> header = List.of(lines.get(0).split(",", -1));
            int idx = header.indexOf("schema_name");
            if (idx < 0) {
                throw new IllegalStateException("vendor file has no schema_name column: " + csvFile);
            }
            String[] firstRow = lines.get(1).split(",", -1);
            return firstRow[idx];
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    public static List<IngestedRecord> readBackAll(Path vintageDir) {
        return PointInTimeVintageStore.readAllUnder(vintageDir);
    }
}
