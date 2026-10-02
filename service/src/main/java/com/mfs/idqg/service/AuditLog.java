package com.mfs.idqg.service;

import com.mfs.idqg.domain.Violation;
import com.mfs.idqg.rules.RulePredicates;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Append-only audit log plus the quarantine-records table. Every quarantine
 * decision is one line: a quarantine_id, the rule that fired, and a
 * self-contained snapshot of the row plus its comparison context. Replaying
 * a quarantine_id re-runs the matching RulePredicates method against ONLY
 * that stored snapshot (no access to the original CSVs) and must recompute
 * the same "violates" decision, which is the reproducibility claim.
 *
 * The log is a plain delimited text format rather than JSON so the service
 * has zero third-party dependencies; fields are separated by a control
 * character (\u0001) that never appears in this project's synthetic data.
 */
public final class AuditLog {
    private static final String FS = "\u0001";
    private static final String KV = "\u0002";

    public record QuarantineRecord(String quarantineId, String ruleName, String ruleCategory,
                                     String ownerName, String sourceFile, String sourceRow,
                                     String columnName, String detail) {
    }

    public static List<QuarantineRecord> writeAll(List<Violation> violations, String runId,
                                                    Path quarantineCsv, Path auditLogFile) throws IOException {
        List<QuarantineRecord> records = new ArrayList<>();
        Files.createDirectories(quarantineCsv.getParent());
        try (BufferedWriter csv = Files.newBufferedWriter(quarantineCsv, StandardCharsets.UTF_8);
             BufferedWriter audit = Files.newBufferedWriter(auditLogFile, StandardCharsets.UTF_8,
                     StandardOpenOption.CREATE, StandardOpenOption.APPEND)) {
            csv.write("quarantine_id,rule_name,rule_category,owner_name,source_file,source_row,column_name,detail");
            csv.newLine();
            for (Violation v : violations) {
                String id = UUID.randomUUID().toString();
                String owner = OwnerAssignment.ownerFor(v.ruleCategory);
                records.add(new QuarantineRecord(id, v.ruleName, v.ruleCategory, owner,
                        v.sourceFile, v.sourceRow, v.columnName, v.detail));
                csv.write(String.join(",", id, v.ruleName, v.ruleCategory, csvSafe(owner),
                        v.sourceFile, v.sourceRow, v.columnName, csvSafe(v.detail)));
                csv.newLine();

                StringBuilder line = new StringBuilder();
                line.append(id).append(FS).append(v.ruleName).append(FS).append(v.ruleCategory).append(FS)
                        .append(owner).append(FS).append(v.sourceFile).append(FS).append(v.sourceRow).append(FS)
                        .append(runId).append(FS).append(Instant.now()).append(FS)
                        .append(serializeSnapshot(v.snapshot));
                audit.write(line.toString());
                audit.newLine();
            }
        }
        return records;
    }

    private static String csvSafe(String s) {
        return s == null ? "" : s.replace(",", ";");
    }

    private static String serializeSnapshot(Map<String, String> snapshot) {
        StringBuilder sb = new StringBuilder();
        boolean first = true;
        for (Map.Entry<String, String> e : snapshot.entrySet()) {
            if (!first) {
                sb.append(FS);
            }
            first = false;
            sb.append(e.getKey()).append(KV).append(e.getValue() == null ? "" : e.getValue());
        }
        return sb.toString();
    }

    private static Map<String, String> deserializeSnapshot(String s) {
        Map<String, String> m = new LinkedHashMap<>();
        if (s.isEmpty()) {
            return m;
        }
        for (String part : s.split(FS)) {
            int idx = part.indexOf(KV);
            if (idx >= 0) {
                m.put(part.substring(0, idx), part.substring(idx + 1));
            }
        }
        return m;
    }

    /** Replays every line in the audit log and returns [total, reproduced]. */
    public static int[] replayAll(Path auditLogFile) throws IOException {
        int total = 0;
        int reproduced = 0;
        for (String line : Files.readAllLines(auditLogFile, StandardCharsets.UTF_8)) {
            if (line.isEmpty()) {
                continue;
            }
            total++;
            String[] parts = line.split(FS, 9);
            String ruleName = parts[1];
            Map<String, String> snapshot = deserializeSnapshot(parts[8]);
            if (reapply(ruleName, snapshot)) {
                reproduced++;
            }
        }
        return new int[]{total, reproduced};
    }

    private static boolean reapply(String ruleName, Map<String, String> snapshot) {
        return switch (ruleName) {
            case "stale_price" -> RulePredicates.stalePrice(snapshot);
            case "missing_price" -> RulePredicates.missingPrice(snapshot);
            case "future_dated_price" -> RulePredicates.futureDatedPrice(snapshot);
            case "negative_price" -> RulePredicates.negativePrice(snapshot);
            case "zero_price" -> RulePredicates.zeroPrice(snapshot);
            case "price_band_breach" -> RulePredicates.priceBandBreach(snapshot);
            case "price_currency_mismatch" -> RulePredicates.priceCurrencyMismatch(snapshot);
            case "missing_benchmark" -> RulePredicates.missingBenchmark(snapshot);
            case "duplicate_identifier" -> RulePredicates.duplicateIdentifier(snapshot);
            case "missing_asset_class" -> RulePredicates.missingAssetClass(snapshot);
            case "missing_currency_code" -> RulePredicates.missingCurrencyCode(snapshot);
            case "invalid_currency_code" -> RulePredicates.invalidCurrencyCode(snapshot);
            case "missing_sector_for_equity" -> RulePredicates.missingSectorForEquity(snapshot);
            case "missing_maturity_date_for_bond" -> RulePredicates.missingMaturityDateForBond(snapshot);
            case "coupon_rate_out_of_range_for_bond" -> RulePredicates.couponRateOutOfRangeForBond(snapshot);
            case "holdings_to_nav_tie_out" -> RulePredicates.holdingsToNavTieOut(snapshot);
            case "orphan_position" -> RulePredicates.orphanPosition(snapshot);
            case "negative_position_quantity" -> RulePredicates.negativePositionQuantity(snapshot);
            case "duplicate_position_row" -> RulePredicates.duplicatePositionRow(snapshot);
            case "matured_bond_still_priced" -> RulePredicates.maturedBondStillPriced(snapshot);
            case "fx_rate_missing_for_nonbase_currency" -> RulePredicates.fxRateMissingForNonbaseCurrency(snapshot);
            case "position_date_mismatch" -> RulePredicates.positionDateMismatch(snapshot);
            default -> throw new IllegalArgumentException("unknown rule " + ruleName);
        };
    }
}
