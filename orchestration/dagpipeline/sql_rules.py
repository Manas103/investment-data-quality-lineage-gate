"""The 30 named SQL rules for the daily orchestrated ingestion run.

Every rule is literal SQL (DuckDB; PostgreSQL-compatible syntax throughout,
the same designed-in-Postgres-measured-in-DuckDB pattern the sibling
`dbt/` half of this repository already discloses, since no standalone
PostgreSQL server was reachable in this build environment), run against a
`vendor_records` table built from every row of every vendor file ingested
that day, plus a `file_manifest` table of one row per file for the
file-level and schema-level rules.

26 rules are row-level (their SQL returns the violating (schema,
source_file, source_row) tuples directly, which is what gives every
quarantine its column-level lineage); 4 are file- or schema-level
aggregates (`empty_file`, `schema_missing_any_delivery`,
`file_count_below_expected`, `row_count_anomaly_low`,
`row_count_anomaly_high`... see ROW_LEVEL_RULES / AGGREGATE_RULES below for
the exact split, which totals 30).
"""
from __future__ import annotations

from dataclasses import dataclass

EXPECTED_ROWS_PER_FILE = 15
EXPECTED_FILES_PER_SCHEMA = 44


@dataclass(frozen=True)
class SqlRule:
    name: str
    level: str  # "row", "file", or "schema"
    sql: str  # a query over vendor_records / file_manifest returning violating keys


ROW_LEVEL_RULES: list[SqlRule] = [
    SqlRule("missing_instrument_id", "row",
            "SELECT schema, source_file, source_row FROM vendor_records "
            "WHERE instrument_id IS NULL OR trim(instrument_id) = ''"),
    SqlRule("missing_currency", "row",
            "SELECT schema, source_file, source_row FROM vendor_records "
            "WHERE currency IS NULL OR trim(currency) = ''"),
    SqlRule("missing_as_of_date", "row",
            "SELECT schema, source_file, source_row FROM vendor_records "
            "WHERE as_of_date IS NULL OR trim(as_of_date) = ''"),
    SqlRule("missing_value1", "row",
            "SELECT schema, source_file, source_row FROM vendor_records WHERE value1 IS NULL"),
    SqlRule("missing_value2", "row",
            "SELECT schema, source_file, source_row FROM vendor_records WHERE value2 IS NULL"),
    SqlRule("missing_source_vendor", "row",
            "SELECT schema, source_file, source_row FROM vendor_records "
            "WHERE source_vendor IS NULL OR trim(source_vendor) = ''"),
    SqlRule("value1_below_schema_min", "row",
            "SELECT schema, source_file, source_row FROM vendor_records WHERE value1 < value1_min"),
    SqlRule("value1_above_schema_max", "row",
            "SELECT schema, source_file, source_row FROM vendor_records WHERE value1 > value1_max"),
    SqlRule("value1_negative_when_disallowed", "row",
            "SELECT schema, source_file, source_row FROM vendor_records "
            "WHERE value1 < 0 AND NOT allow_negative_value1"),
    SqlRule("value1_gross_outlier_10x_max", "row",
            "SELECT schema, source_file, source_row FROM vendor_records WHERE value1 > value1_max * 10"),
    SqlRule("value2_below_zero", "row",
            "SELECT schema, source_file, source_row FROM vendor_records WHERE value2 < 0"),
    SqlRule("value2_above_one", "row",
            "SELECT schema, source_file, source_row FROM vendor_records WHERE value2 > 1"),
    SqlRule("non_usd_currency", "row",
            "SELECT schema, source_file, source_row FROM vendor_records WHERE currency <> 'USD'"),
    SqlRule("unknown_source_vendor", "row",
            "SELECT schema, source_file, source_row FROM vendor_records "
            "WHERE source_vendor NOT IN ('vendor_a', 'vendor_b', 'vendor_c')"),
    SqlRule("stale_as_of_date_past", "row",
            "SELECT schema, source_file, source_row FROM vendor_records WHERE as_of_date < '2026-10-05'"),
    SqlRule("future_dated_record", "row",
            "SELECT schema, source_file, source_row FROM vendor_records WHERE as_of_date > '2026-10-05'"),
    SqlRule("instrument_id_wrong_prefix", "row",
            "SELECT schema, source_file, source_row FROM vendor_records "
            "WHERE upper(substr(instrument_id, 1, 3)) <> upper(substr(asset_class, 1, 3))"),
    SqlRule("duplicate_instrument_within_file", "row",
            "SELECT schema, source_file, source_row FROM vendor_records v WHERE EXISTS ("
            "  SELECT 1 FROM vendor_records v2 WHERE v2.schema = v.schema AND v2.source_file = v.source_file "
            "  AND v2.instrument_id = v.instrument_id AND v2.source_row <> v.source_row)"),
    SqlRule("source_row_negative", "row",
            "SELECT schema, source_file, source_row FROM vendor_records WHERE source_row < 0"),
    SqlRule("source_file_blank", "row",
            "SELECT schema, source_file, source_row FROM vendor_records "
            "WHERE source_file IS NULL OR trim(source_file) = ''"),
    SqlRule("schema_label_mismatch", "row",
            "SELECT schema, source_file, source_row FROM vendor_records "
            "WHERE position(schema in source_file) <> 1"),
    SqlRule("currency_not_three_letters", "row",
            "SELECT schema, source_file, source_row FROM vendor_records WHERE length(currency) <> 3"),
    SqlRule("instrument_id_wrong_length", "row",
            "SELECT schema, source_file, source_row FROM vendor_records WHERE length(instrument_id) <> 8"),
    SqlRule("value1_exactly_zero", "row",
            "SELECT schema, source_file, source_row FROM vendor_records WHERE value1 = 0"),
    SqlRule("as_of_date_wrong_format", "row",
            "SELECT schema, source_file, source_row FROM vendor_records "
            "WHERE as_of_date !~ '^[0-9]{4}-[0-9]{2}-[0-9]{2}$'"),
    SqlRule("value2_is_integer_valued", "row",
            "SELECT schema, source_file, source_row FROM vendor_records "
            "WHERE value2 = floor(value2) AND value2 > 0"),
]

AGGREGATE_RULES: list[SqlRule] = [
    SqlRule("empty_file", "file",
            "SELECT schema, file_name AS source_file FROM file_manifest WHERE row_count = 0"),
    SqlRule("row_count_anomaly_low", "file",
            f"SELECT schema, file_name AS source_file FROM file_manifest "
            f"WHERE row_count < {EXPECTED_ROWS_PER_FILE} AND row_count > 0"),
    SqlRule("row_count_anomaly_high", "file",
            f"SELECT schema, file_name AS source_file FROM file_manifest WHERE row_count > {EXPECTED_ROWS_PER_FILE}"),
    SqlRule("file_count_below_expected", "schema",
            f"SELECT schema FROM (SELECT schema, count(*) AS n FROM file_manifest GROUP BY schema) "
            f"WHERE n < {EXPECTED_FILES_PER_SCHEMA}"),
]

ALL_SQL_RULES: list[SqlRule] = ROW_LEVEL_RULES + AGGREGATE_RULES

assert len(ALL_SQL_RULES) == 30
