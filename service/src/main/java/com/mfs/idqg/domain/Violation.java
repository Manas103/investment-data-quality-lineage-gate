package com.mfs.idqg.domain;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * One quarantine candidate: the rule that fired, the exact source file and
 * row it came from (column-level lineage), and a self-contained snapshot
 * carrying both the row's own fields and whatever comparison context the
 * rule needed (dataset max date, a sibling's value, a true total, and so
 * on), so that the audit log entry built from this snapshot alone is enough
 * to replay the same decision without re-reading the original CSVs.
 */
public final class Violation {
    public final String ruleName;
    public final String ruleCategory;
    public final String sourceFile;
    public final String sourceRow;
    public final String columnName;
    public final String detail;
    public final Map<String, String> snapshot;

    public Violation(String ruleName, String ruleCategory, String sourceFile, String sourceRow,
                      String columnName, String detail, Map<String, String> snapshot) {
        this.ruleName = ruleName;
        this.ruleCategory = ruleCategory;
        this.sourceFile = sourceFile;
        this.sourceRow = sourceRow;
        this.columnName = columnName;
        this.detail = detail;
        this.snapshot = new LinkedHashMap<>(snapshot);
    }
}
