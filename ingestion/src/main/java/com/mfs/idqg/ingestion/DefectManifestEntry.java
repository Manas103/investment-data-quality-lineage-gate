package com.mfs.idqg.ingestion;

/** One seeded defect: the check it should trip, and exactly where it was planted. */
public record DefectManifestEntry(String checkName, String sourceFile, int sourceRow) {
}
