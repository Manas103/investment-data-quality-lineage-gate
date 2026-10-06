"""Runs the 30 SQL rules against an ingested day's records and builds the
column-level lineage for every quarantined failure.

`load_records_into_duckdb` builds the `vendor_records` and `file_manifest`
tables a rule's SQL expects; `run_all_rules` runs every rule in
`sql_rules.ALL_SQL_RULES` and returns one quarantine entry per violation,
each carrying the rule name and the exact (schema, source_file, source_row)
lineage coordinate it came from.
"""
from __future__ import annotations

from dataclasses import dataclass

import duckdb

from .schemas import SCHEMA_BY_NAME
from .sql_rules import ALL_SQL_RULES, SqlRule


@dataclass(frozen=True)
class QuarantineEntry:
    rule: str
    schema: str
    source_file: str
    source_row: int | None


def connect_with_records(records: list[dict]) -> duckdb.DuckDBPyConnection:
    con = duckdb.connect(database=":memory:")
    con.execute(
        """
        CREATE TABLE vendor_records (
            as_of_date VARCHAR, instrument_id VARCHAR, value1 DOUBLE, value2 DOUBLE,
            currency VARCHAR, source_vendor VARCHAR, schema VARCHAR, source_file VARCHAR,
            source_row INTEGER, asset_class VARCHAR, allow_negative_value1 BOOLEAN,
            value1_min DOUBLE, value1_max DOUBLE
        )
        """
    )
    rows = []
    for r in records:
        schema = SCHEMA_BY_NAME[r["schema"]]
        rows.append((
            r["as_of_date"], r["instrument_id"],
            float(r["value1"]) if r["value1"] not in (None, "") else None,
            float(r["value2"]) if r["value2"] not in (None, "") else None,
            r["currency"], r["source_vendor"], r["schema"], r["source_file"],
            int(r["source_row"]), schema.asset_class, schema.allow_negative_value1,
            schema.value1_min, schema.value1_max,
        ))
    con.executemany(
        "INSERT INTO vendor_records VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?)", rows
    )

    con.execute("CREATE TABLE file_manifest (schema VARCHAR, file_name VARCHAR, row_count INTEGER)")
    con.execute(
        "INSERT INTO file_manifest "
        "SELECT schema, source_file, count(*) FROM vendor_records GROUP BY schema, source_file"
    )
    # file_manifest must also include genuinely empty files, which have no rows
    # in vendor_records at all; the caller passes those in separately if any exist.
    return con


def run_all_rules(records: list[dict], empty_files: list[tuple[str, str]] | None = None) -> list[QuarantineEntry]:
    con = connect_with_records(records)
    if empty_files:
        con.executemany(
            "INSERT INTO file_manifest VALUES (?, ?, 0)", empty_files
        )
    try:
        entries: list[QuarantineEntry] = []
        for rule in ALL_SQL_RULES:
            entries.extend(run_rule_on_connection(rule, con))
        return entries
    finally:
        con.close()


def run_single_rule(rule: SqlRule, records: list[dict]) -> list[QuarantineEntry]:
    con = connect_with_records(records)
    try:
        return run_rule_on_connection(rule, con)
    finally:
        con.close()


def run_rule_on_connection(rule: SqlRule, con: duckdb.DuckDBPyConnection) -> list[QuarantineEntry]:
    result = con.execute(rule.sql).fetchall()
    columns = [d[0] for d in con.description]
    entries = []
    for row in result:
        row_dict = dict(zip(columns, row))
        entries.append(
            QuarantineEntry(
                rule=rule.name,
                schema=row_dict.get("schema", ""),
                source_file=row_dict.get("source_file", ""),
                source_row=row_dict.get("source_row"),
            )
        )
    return entries
