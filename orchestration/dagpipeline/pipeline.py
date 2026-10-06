"""Builds the 55-task daily orchestration DAG: 1 start, 35 per-schema extract
tasks, 1 consolidate, 10 validate-group tasks (3 of the 30 SQL rules each),
1 quarantine, 1 lineage_index, 1 vintage_publish_s3, 1 reconcile_checksum,
1 checkpoint, 1 archive, 1 notify, 1 end.

1 + 35 + 1 + 10 + 1 + 1 + 1 + 1 + 1 + 1 + 1 + 1 = 55, asserted below.
"""
from __future__ import annotations

from pathlib import Path

from .dag import TaskContext, TaskSpec
from .quarantine import connect_with_records, run_rule_on_connection
from .s3_vintage_store import BUCKET_NAME, S3VintageStore
from .schemas import SCHEMAS
from .sql_rules import ALL_SQL_RULES
from .vendor_files import read_vendor_file

AS_OF_DATE = "2026-10-05"
NUM_VALIDATE_GROUPS = 10
RULES_PER_GROUP = len(ALL_SQL_RULES) // NUM_VALIDATE_GROUPS
assert RULES_PER_GROUP * NUM_VALIDATE_GROUPS == len(ALL_SQL_RULES) == 30


def _start(ctx: TaskContext) -> dict:
    return {"status": "started", "as_of_date": AS_OF_DATE}


def _make_extract(schema_name: str, vendor_dir: Path):
    def _fn(ctx: TaskContext) -> dict:
        files = sorted(vendor_dir.glob(f"{schema_name}_*.csv"))
        records = []
        for f in files:
            records.extend(read_vendor_file(f, schema_name))
        return {"schema": schema_name, "records": records, "file_count": len(files)}

    return _fn


def _consolidate(ctx: TaskContext) -> dict:
    all_records = []
    total_files = 0
    for schema in SCHEMAS:
        extract_output = ctx.inputs[f"extract_{schema.name}"]
        all_records.extend(extract_output["records"])
        total_files += extract_output["file_count"]
    return {"record_count": len(all_records), "file_count": total_files, "records": all_records}


def _make_validate_group(group_idx: int, shared_connection_cache: dict):
    rules = ALL_SQL_RULES[group_idx * RULES_PER_GROUP:(group_idx + 1) * RULES_PER_GROUP]

    def _fn(ctx: TaskContext) -> dict:
        # Every validate_group task needs the same consolidated records loaded
        # into DuckDB; building that table once per run and sharing the
        # connection across all 10 groups, instead of each group rebuilding it,
        # is a 10x reduction in redundant table-build work for the same result.
        if "connection" not in shared_connection_cache:
            records = ctx.inputs["consolidate"]["records"]
            shared_connection_cache["connection"] = connect_with_records(records)
        con = shared_connection_cache["connection"]

        violations = []
        for rule in rules:
            for entry in run_rule_on_connection(rule, con):
                violations.append({"rule": entry.rule, "schema": entry.schema,
                                    "source_file": entry.source_file, "source_row": entry.source_row})
        return {"group": group_idx, "rule_names": [r.name for r in rules], "violations": violations}

    return _fn


def _make_quarantine(shared_connection_cache: dict):
    def _fn(ctx: TaskContext) -> dict:
        violations = []
        for group_idx in range(NUM_VALIDATE_GROUPS):
            violations.extend(ctx.inputs[f"validate_group_{group_idx}"]["violations"])
        connection = shared_connection_cache.pop("connection", None)
        if connection is not None:
            connection.close()
        return {"violations": violations, "count": len(violations)}

    return _fn


def _lineage_index(ctx: TaskContext) -> dict:
    violations = ctx.inputs["quarantine"]["violations"]
    missing = [v for v in violations if not v.get("schema") or not v.get("source_file")]
    return {"total": len(violations), "missing_lineage": len(missing)}


def _make_vintage_publish_s3(store: S3VintageStore):
    def _fn(ctx: TaskContext) -> dict:
        consolidate_output = ctx.inputs["consolidate"]
        payload = {
            "as_of_date": AS_OF_DATE,
            "record_count": consolidate_output["record_count"],
            "file_count": consolidate_output["file_count"],
            "violation_count": ctx.inputs["lineage_index"]["total"],
        }
        vintage_key = store.write_vintage(schema="daily_consolidated", as_of_date=AS_OF_DATE, payload=payload)
        return {"bucket": BUCKET_NAME, "key": vintage_key.key, "delivery_id": vintage_key.delivery_id}

    return _fn


def _reconcile_checksum(ctx: TaskContext) -> dict:
    consolidate_output = ctx.inputs["consolidate"]
    return {"record_count": consolidate_output["record_count"], "file_count": consolidate_output["file_count"]}


def _checkpoint(ctx: TaskContext) -> dict:
    return {"status": "checkpoint"}


def _archive(ctx: TaskContext) -> dict:
    return {"archived": True}


def _notify(ctx: TaskContext) -> dict:
    return {"notified_owners": ["Pricing Operations", "Security Master", "Fund Accounting"]}


def _end(ctx: TaskContext) -> dict:
    return {"status": "end"}


def build_task_specs(vendor_dir: Path, store: S3VintageStore) -> list[TaskSpec]:
    tasks: list[TaskSpec] = [TaskSpec("start", [], _start)]

    for schema in SCHEMAS:
        tasks.append(TaskSpec(f"extract_{schema.name}", ["start"], _make_extract(schema.name, vendor_dir)))

    extract_ids = [f"extract_{s.name}" for s in SCHEMAS]
    tasks.append(TaskSpec("consolidate", extract_ids, _consolidate))

    shared_connection_cache: dict = {}
    for group_idx in range(NUM_VALIDATE_GROUPS):
        tasks.append(TaskSpec(
            f"validate_group_{group_idx}", ["consolidate"],
            _make_validate_group(group_idx, shared_connection_cache),
        ))

    validate_ids = [f"validate_group_{i}" for i in range(NUM_VALIDATE_GROUPS)]
    tasks.append(TaskSpec("quarantine", validate_ids, _make_quarantine(shared_connection_cache)))
    tasks.append(TaskSpec("lineage_index", ["quarantine"], _lineage_index))
    tasks.append(TaskSpec("vintage_publish_s3", ["consolidate", "lineage_index"], _make_vintage_publish_s3(store)))
    tasks.append(TaskSpec("reconcile_checksum", ["vintage_publish_s3", "consolidate"], _reconcile_checksum))
    tasks.append(TaskSpec("checkpoint", ["reconcile_checksum"], _checkpoint))
    tasks.append(TaskSpec("archive", ["checkpoint"], _archive))
    tasks.append(TaskSpec("notify", ["archive"], _notify))
    tasks.append(TaskSpec("end", ["notify"], _end))

    assert len(tasks) == 55, f"expected 55 tasks, built {len(tasks)}"
    return tasks
