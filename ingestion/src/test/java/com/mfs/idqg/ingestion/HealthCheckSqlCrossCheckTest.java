package com.mfs.idqg.ingestion;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The 14 health checks exist twice: as Java predicates (HealthCheckEngine, what is actually run
 * at the 500-defect and 1.2M-record scale) and as literal SQL (sql/health_checks/*.sql), each
 * written independently against the same vendor_record shape. This test loads the defect corpus
 * into a real H2 database (PostgreSQL-compatibility mode) and asserts the SQL and Java versions
 * of every one of the 14 checks agree on the exact same set of (source_file, source_row) pairs.
 */
class HealthCheckSqlCrossCheckTest {

    private static final Map<String, Boolean> CHECK_NEEDS_DATE_PARAM = new TreeMap<>(Map.of(
            HealthChecks.STALE_FEED, true,
            HealthChecks.FUTURE_DATED_RECORD, true,
            HealthChecks.LATE_ARRIVING_RECORD, true));

    @Test
    void sqlAndJavaAgreeOnEveryCheckOverTheDefectCorpus(@TempDir Path tempDir) throws Exception {
        LocalDate today = LocalDate.of(2026, 10, 5);
        SchemaRegistry registry = new SchemaRegistry();
        InstrumentMaster instruments = new InstrumentMaster(2000);
        VendorFileGenerator generator = new VendorFileGenerator(registry, instruments, today);

        Path csvDir = tempDir.resolve("csv");
        generator.generateDefectCorpus(csvDir, 6, 150, 1234L);

        IngestionPipeline pipeline = new IngestionPipeline(registry);
        Path vintageDir = tempDir.resolve("vintages");
        pipeline.ingestDirectory(csvDir, vintageDir);
        List<IngestedRecord> records = IngestionPipeline.readBackAll(vintageDir);

        HealthCheckEngine engine = new HealthCheckEngine(registry, instruments, today);
        List<QuarantineViolation> javaViolations = engine.runAll(records);

        Path projectRoot = Path.of("").toAbsolutePath();
        Path sqlDir = projectRoot.resolve("sql").resolve("health_checks");
        assertTrue(Files.exists(sqlDir), "sql/health_checks must exist at " + sqlDir);

        try (Connection conn = DriverManager.getConnection("jdbc:h2:mem:idqg_ingestion;MODE=PostgreSQL;DB_CLOSE_DELAY=-1")) {
            runScript(conn, projectRoot.resolve("sql").resolve("schema_vendor_record.sql"));
            loadVendorRecords(conn, records);
            loadInstrumentMaster(conn, instruments);
            loadSchemaBounds(conn, registry);

            for (String check : HealthChecks.ALL) {
                Set<String> javaKeys = new HashSet<>();
                for (QuarantineViolation v : javaViolations) {
                    if (v.checkName().equals(check)) {
                        javaKeys.add(v.sourceFile() + "|" + v.sourceRow());
                    }
                }
                Set<String> sqlKeys = runCheck(conn, sqlDir.resolve(check + ".sql"), today,
                        CHECK_NEEDS_DATE_PARAM.getOrDefault(check, false));
                assertEquals(javaKeys, sqlKeys, "SQL and Java disagree on check " + check);
            }
        }
    }

    private static void runScript(Connection conn, Path script) throws IOException, SQLException {
        String sql = Files.readString(script);
        try (var stmt = conn.createStatement()) {
            for (String part : sql.split(";")) {
                String trimmed = part.trim();
                if (!trimmed.isEmpty()) {
                    stmt.execute(trimmed);
                }
            }
        }
    }

    private static void loadVendorRecords(Connection conn, List<IngestedRecord> records) throws SQLException {
        String insert = "INSERT INTO vendor_record VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?)";
        try (PreparedStatement ps = conn.prepareStatement(insert)) {
            for (IngestedRecord r : records) {
                ps.setString(1, r.sourceFile);
                ps.setInt(2, r.sourceRow);
                ps.setString(3, r.vendor);
                ps.setString(4, r.schemaName);
                ps.setString(5, r.instrumentId);
                ps.setString(6, r.asOfDateRaw);
                ps.setString(7, r.receivedAtRaw);
                ps.setString(8, r.currency);
                ps.setString(9, r.value1Raw);
                ps.setString(10, r.value2Raw);
                Double numeric = parseOrNull(r.value1Raw);
                if (numeric == null) {
                    ps.setNull(11, java.sql.Types.DOUBLE);
                } else {
                    ps.setDouble(11, numeric);
                }
                ps.setInt(12, r.declaredColumnCount);
                ps.setInt(13, r.actualColumnCount);
                ps.setBoolean(14, r.missingRequiredColumn);
                ps.addBatch();
            }
            ps.executeBatch();
        }
    }

    private static Double parseOrNull(String s) {
        if (s == null || s.isBlank()) {
            return null;
        }
        try {
            return Double.parseDouble(s);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static void loadInstrumentMaster(Connection conn, InstrumentMaster instruments) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement("INSERT INTO instrument_master VALUES (?)")) {
            for (String id : instruments.instrumentIds()) {
                ps.setString(1, id);
                ps.addBatch();
            }
            ps.executeBatch();
        }
    }

    private static void loadSchemaBounds(Connection conn, SchemaRegistry registry) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement("INSERT INTO schema_bounds VALUES (?,?,?)")) {
            for (VendorSchema s : registry.all()) {
                ps.setString(1, s.name());
                ps.setDouble(2, s.value1UpperBound());
                ps.setBoolean(3, s.allowNegativeValue1());
                ps.addBatch();
            }
            ps.executeBatch();
        }
    }

    private static Set<String> runCheck(Connection conn, Path sqlFile, LocalDate today, boolean needsDateParam) throws IOException, SQLException {
        String sql = stripComments(Files.readString(sqlFile));
        Set<String> keys = new HashSet<>();
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            if (needsDateParam) {
                int paramCount = countParams(sql);
                for (int i = 1; i <= paramCount; i++) {
                    ps.setString(i, today.toString());
                }
            }
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    keys.add(rs.getString(1) + "|" + rs.getInt(2));
                }
            }
        }
        return keys;
    }

    private static int countParams(String sql) {
        int count = 0;
        for (char c : sql.toCharArray()) {
            if (c == '?') {
                count++;
            }
        }
        return count;
    }

    private static String stripComments(String sql) {
        return sql.lines()
                .filter(line -> !line.trim().startsWith("--"))
                .reduce((a, b) -> a + "\n" + b)
                .orElse("");
    }
}
