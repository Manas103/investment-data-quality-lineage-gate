package com.mfs.idqg.ingestion;

import blue.strategic.parquet.ParquetReader;
import blue.strategic.parquet.ParquetWriter;
import org.apache.parquet.schema.MessageType;
import org.apache.parquet.schema.Types;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import static org.apache.parquet.schema.LogicalTypeAnnotation.stringType;
import static org.apache.parquet.schema.PrimitiveType.PrimitiveTypeName.BINARY;
import static org.apache.parquet.schema.PrimitiveType.PrimitiveTypeName.BOOLEAN;
import static org.apache.parquet.schema.PrimitiveType.PrimitiveTypeName.INT32;

/**
 * Writes one ingested vendor file as one immutable point-in-time Parquet vintage (never
 * overwritten: a restatement of the same (schema, as-of-date) is a brand new file, named by its
 * own received-at and a uniquifying suffix, alongside the original). Reads back through the real
 * Apache Parquet column format (parquet-floor, no Hadoop cluster dependency), proving the
 * round-trip rather than assuming the writer and reader agree.
 */
public final class PointInTimeVintageStore {

    public static final MessageType SCHEMA = new MessageType("vendor_record",
            Types.required(BINARY).as(stringType()).named("source_file"),
            Types.required(INT32).named("source_row"),
            Types.required(BINARY).as(stringType()).named("vendor"),
            Types.required(BINARY).as(stringType()).named("schema_name"),
            Types.required(BINARY).as(stringType()).named("instrument_id"),
            Types.required(BINARY).as(stringType()).named("as_of_date"),
            Types.required(BINARY).as(stringType()).named("received_at"),
            Types.required(BINARY).as(stringType()).named("currency"),
            Types.required(BINARY).as(stringType()).named("value1"),
            Types.required(BINARY).as(stringType()).named("value2"),
            Types.required(INT32).named("declared_column_count"),
            Types.required(INT32).named("actual_column_count"),
            Types.required(BOOLEAN).named("missing_required_column"));

    private PointInTimeVintageStore() {
    }

    public static void writeVintage(Path parquetFile, List<IngestedRecord> records) {
        try {
            Files.createDirectories(parquetFile.getParent());
            try (ParquetWriter<IngestedRecord> writer = ParquetWriter.writeFile(SCHEMA, parquetFile.toFile(),
                    (record, valueWriter) -> {
                        valueWriter.write("source_file", record.sourceFile);
                        valueWriter.write("source_row", record.sourceRow);
                        valueWriter.write("vendor", record.vendor);
                        valueWriter.write("schema_name", record.schemaName);
                        valueWriter.write("instrument_id", record.instrumentId);
                        valueWriter.write("as_of_date", record.asOfDateRaw);
                        valueWriter.write("received_at", record.receivedAtRaw);
                        valueWriter.write("currency", record.currency);
                        valueWriter.write("value1", record.value1Raw);
                        valueWriter.write("value2", record.value2Raw);
                        valueWriter.write("declared_column_count", record.declaredColumnCount);
                        valueWriter.write("actual_column_count", record.actualColumnCount);
                        valueWriter.write("missing_required_column", record.missingRequiredColumn);
                    })) {
                for (IngestedRecord record : records) {
                    writer.write(record);
                }
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    public static List<IngestedRecord> readVintage(Path parquetFile) {
        blue.strategic.parquet.Hydrator<List<Object>, IngestedRecord> hydrator = new blue.strategic.parquet.Hydrator<>() {
            @Override
            public List<Object> start() {
                return new ArrayList<>(java.util.Collections.nCopies(13, null));
            }

            @Override
            public List<Object> add(List<Object> target, String heading, Object value) {
                target.set(indexOf(heading), value);
                return target;
            }

            @Override
            public IngestedRecord finish(List<Object> target) {
                return new IngestedRecord(
                        (String) target.get(0), (Integer) target.get(1), (String) target.get(2),
                        (String) target.get(3), (String) target.get(4), (String) target.get(5),
                        (String) target.get(6), (String) target.get(7), (String) target.get(8),
                        (String) target.get(9), (Integer) target.get(10), (Integer) target.get(11),
                        (Boolean) target.get(12));
            }
        };
        try (Stream<IngestedRecord> stream = ParquetReader.streamContent(parquetFile.toFile(),
                blue.strategic.parquet.HydratorSupplier.constantly(hydrator))) {
            return stream.collect(java.util.stream.Collectors.toCollection(ArrayList::new));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static int indexOf(String heading) {
        return switch (heading) {
            case "source_file" -> 0;
            case "source_row" -> 1;
            case "vendor" -> 2;
            case "schema_name" -> 3;
            case "instrument_id" -> 4;
            case "as_of_date" -> 5;
            case "received_at" -> 6;
            case "currency" -> 7;
            case "value1" -> 8;
            case "value2" -> 9;
            case "declared_column_count" -> 10;
            case "actual_column_count" -> 11;
            case "missing_required_column" -> 12;
            default -> throw new IllegalStateException("unknown column: " + heading);
        };
    }

    public static List<IngestedRecord> readAllUnder(Path dir) {
        try (Stream<Path> walk = Files.walk(dir)) {
            List<Path> files = walk.filter(p -> p.toString().endsWith(".parquet")).toList();
            List<IngestedRecord> all = new ArrayList<>();
            for (Path file : files) {
                all.addAll(readVintage(file));
            }
            return all;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
