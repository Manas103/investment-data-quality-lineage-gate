package com.mfs.idqg;

import com.mfs.idqg.domain.Violation;
import com.mfs.idqg.rules.RuleEngine;
import com.mfs.idqg.service.AuditLog;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * CLI entry point: validates one generated corpus against the 22 named
 * rules, writes the quarantine table and audit log, replays the whole audit
 * log to check reproducibility, and (if a defect manifest is given) scores
 * the seeded-defect catch rate.
 *
 * Usage: java -jar idqg.jar <corpus-dir> <output-dir> [defect_manifest.json]
 */
public final class Main {
    public static void main(String[] args) throws Exception {
        if (args.length < 2) {
            System.err.println("usage: idqg <corpus-dir> <output-dir> [defect_manifest.json]");
            System.exit(2);
        }
        Path corpusDir = Path.of(args[0]);
        Path outDir = Path.of(args[1]);
        Files.createDirectories(outDir);

        RuleEngine engine = new RuleEngine();
        engine.load(corpusDir);
        List<Violation> violations = engine.runAll();

        String runId = "run-" + System.currentTimeMillis();
        Path quarantineCsv = outDir.resolve("quarantine_records.csv");
        Path auditLogFile = outDir.resolve("audit_log.log");
        AuditLog.writeAll(violations, runId, quarantineCsv, auditLogFile);

        System.out.println("=== Investment Data Quality Gate ===");
        System.out.println("instruments: " + engine.instrumentCount());
        System.out.println("pricing rows: " + engine.pricing.size());
        System.out.println("position rows: " + engine.positions.size());
        System.out.println("fund-date rows: " + engine.funds.size());
        System.out.println();
        System.out.println("-- claim: 22 named rules --");
        System.out.println("rules implemented: " + RuleEngine.RULE_CATEGORY.size());
        System.out.println();
        System.out.println("-- quarantine counts by rule --");
        Map<String, Integer> byRule = new TreeMap<>();
        for (Violation v : violations) {
            byRule.merge(v.ruleName, 1, Integer::sum);
        }
        for (Map.Entry<String, Integer> e : byRule.entrySet()) {
            System.out.println("  " + e.getKey() + ": " + e.getValue());
        }
        System.out.println("total quarantined records: " + violations.size());
        System.out.println();

        System.out.println("-- claim: every quarantine has a non-null lineage path and owner --");
        long missingLineage = violations.stream().filter(v -> v.sourceFile == null || v.sourceFile.isEmpty()
                || v.sourceRow == null || v.sourceRow.isEmpty()).count();
        System.out.println("quarantined records missing lineage: " + missingLineage + " / " + violations.size());

        System.out.println();
        System.out.println("-- claim: every quarantine reproducible from the audit log --");
        int[] replay = AuditLog.replayAll(auditLogFile);
        System.out.println("replayed " + replay[0] + " audit log entries, " + replay[1] + " reproduced the original decision");

        if (args.length >= 3) {
            scoreDefects(Path.of(args[2]), violations);
        }
    }

    /** Reads the generator's defect_manifest.tsv (rule_name<TAB>anchor_keys). */
    private static void scoreDefects(Path manifestTsv, List<Violation> violations) throws Exception {
        Set<String> caughtKeys = new HashSet<>();
        for (Violation v : violations) {
            caughtKeys.add(v.ruleName + "|" + v.sourceFile + "|" + v.sourceRow);
        }
        int totalDefects = 0;
        int caughtDefects = 0;
        List<String> missed = new ArrayList<>();
        for (String line : Files.readAllLines(manifestTsv)) {
            if (line.isEmpty()) {
                continue;
            }
            String[] parts = line.split("\t", -1);
            String ruleName = parts[0];
            String anchorKeys = parts.length > 1 ? parts[1] : "";
            totalDefects++;
            boolean allAnchorsCaught = !anchorKeys.isEmpty();
            for (String anchor : anchorKeys.split(";")) {
                if (anchor.isEmpty()) {
                    continue;
                }
                if (!caughtKeys.contains(ruleName + "|" + anchor)) {
                    allAnchorsCaught = false;
                }
            }
            if (allAnchorsCaught) {
                caughtDefects++;
            } else {
                missed.add(ruleName);
            }
        }
        System.out.println();
        System.out.println("-- claim: 45 of 45 seeded defects caught --");
        System.out.println("seeded defects caught: " + caughtDefects + " / " + totalDefects);
        if (!missed.isEmpty()) {
            System.out.println("missed rules: " + missed);
        }
    }
}
