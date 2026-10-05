package com.mfs.idqg.ingestion;

import java.util.List;

/** The 14 named SQL health checks, run by HealthCheckEngine and targeted by SeededDefectInjector. */
public final class HealthChecks {

    public static final String STALE_FEED = "stale_feed";
    public static final String MISSING_REQUIRED_FIELD = "missing_required_field";
    public static final String VALUE_OUT_OF_RANGE = "value_out_of_range";
    public static final String CROSS_SOURCE_DISAGREEMENT = "cross_source_disagreement";
    public static final String DUPLICATE_ROW = "duplicate_row";
    public static final String NEGATIVE_VALUE = "negative_value";
    public static final String FUTURE_DATED_RECORD = "future_dated_record";
    public static final String SCHEMA_DRIFT_COLUMN_COUNT = "schema_drift_column_count";
    public static final String SCHEMA_DRIFT_MISSING_COLUMN = "schema_drift_missing_column";
    public static final String INVALID_CURRENCY_CODE = "invalid_currency_code";
    public static final String LATE_ARRIVING_RECORD = "late_arriving_record";
    public static final String ORPHAN_INSTRUMENT_REFERENCE = "orphan_instrument_reference";
    public static final String ROW_COUNT_ANOMALY = "row_count_anomaly";
    public static final String TYPE_MISMATCH = "type_mismatch";

    public static final List<String> ALL = List.of(
            STALE_FEED, MISSING_REQUIRED_FIELD, VALUE_OUT_OF_RANGE, CROSS_SOURCE_DISAGREEMENT,
            DUPLICATE_ROW, NEGATIVE_VALUE, FUTURE_DATED_RECORD, SCHEMA_DRIFT_COLUMN_COUNT,
            SCHEMA_DRIFT_MISSING_COLUMN, INVALID_CURRENCY_CODE, LATE_ARRIVING_RECORD,
            ORPHAN_INSTRUMENT_REFERENCE, ROW_COUNT_ANOMALY, TYPE_MISMATCH);

    private HealthChecks() {
    }
}
