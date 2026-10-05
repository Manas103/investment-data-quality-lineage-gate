package com.mfs.idqg.ingestion;

/** One quarantined record: which of the 14 checks fired, and the exact source file and row (column-level lineage). */
public record QuarantineViolation(String checkName, String sourceFile, int sourceRow, String detail) {
}
