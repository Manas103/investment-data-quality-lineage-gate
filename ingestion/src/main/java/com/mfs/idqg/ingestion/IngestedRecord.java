package com.mfs.idqg.ingestion;

/**
 * One row from one vendor file, after parsing against its schema's declared column contract.
 * sourceFile and sourceRow are the column-level lineage; actualColumnCount and
 * missingRequiredColumn carry whatever schema-drift the file's own header showed, independent of
 * any individual row's values.
 */
public final class IngestedRecord {
    public final String sourceFile;
    public final int sourceRow;
    public final String vendor;
    public final String schemaName;
    public final String instrumentId;
    public final String asOfDateRaw;
    public final String receivedAtRaw;
    public final String currency;
    public final String value1Raw;
    public final String value2Raw;
    public final int declaredColumnCount;
    public final int actualColumnCount;
    public final boolean missingRequiredColumn;

    public IngestedRecord(String sourceFile, int sourceRow, String vendor, String schemaName,
                           String instrumentId, String asOfDateRaw, String receivedAtRaw,
                           String currency, String value1Raw, String value2Raw,
                           int declaredColumnCount, int actualColumnCount, boolean missingRequiredColumn) {
        this.sourceFile = sourceFile;
        this.sourceRow = sourceRow;
        this.vendor = vendor;
        this.schemaName = schemaName;
        this.instrumentId = instrumentId;
        this.asOfDateRaw = asOfDateRaw;
        this.receivedAtRaw = receivedAtRaw;
        this.currency = currency;
        this.value1Raw = value1Raw;
        this.value2Raw = value2Raw;
        this.declaredColumnCount = declaredColumnCount;
        this.actualColumnCount = actualColumnCount;
        this.missingRequiredColumn = missingRequiredColumn;
    }
}
