"""Runs one full day of the 55-task orchestration DAG end to end: generates
1,500+ vendor files across the 35 schemas, extracts, consolidates, runs all
30 SQL rules, quarantines with lineage, publishes a point-in-time vintage to
a moto-mocked S3 bucket through boto3, and reconciles the record count.

Writes docs/daily_pipeline_output.txt.
"""
from __future__ import annotations

import json
import sys
import tempfile
import time
from pathlib import Path

from moto import mock_aws

sys.path.insert(0, str(Path(__file__).resolve().parent.parent))

from dagpipeline.dag import run_dag
from dagpipeline.pipeline import build_task_specs
from dagpipeline.s3_vintage_store import S3VintageStore
from dagpipeline.vendor_files import generate_one_days_vendor_files


def main() -> None:
    lines = []

    with mock_aws():
        vendor_dir = Path(tempfile.mkdtemp(prefix="dagpipeline_vendor_"))
        t0 = time.time()
        files = generate_one_days_vendor_files(vendor_dir)
        gen_elapsed = time.time() - t0
        lines.append(f"generated {len(files)} vendor files across 35 schemas in {gen_elapsed:.2f}s")

        store = S3VintageStore()
        tasks = build_task_specs(vendor_dir, store)
        lines.append(f"built {len(tasks)} DAG tasks")

        run_dir = Path(tempfile.mkdtemp(prefix="dagpipeline_run_"))
        t0 = time.time()
        result = run_dag(run_dir, tasks)
        run_elapsed = time.time() - t0
        lines.append(f"executed {len(result.executed)} tasks, skipped {len(result.skipped)}, in {run_elapsed:.2f}s")

        quarantine_output = json.loads((run_dir / "outputs" / "quarantine.json").read_text())
        consolidate_output = json.loads((run_dir / "outputs" / "consolidate.json").read_text())
        lineage_output = json.loads((run_dir / "outputs" / "lineage_index.json").read_text())
        vintage_output = json.loads((run_dir / "outputs" / "vintage_publish_s3.json").read_text())
        reconcile_output = json.loads((run_dir / "outputs" / "reconcile_checksum.json").read_text())

        lines.append(f"consolidated record count: {consolidate_output['record_count']}")
        lines.append(f"quarantine violations: {quarantine_output['count']}")
        lines.append(f"violations missing lineage: {lineage_output['missing_lineage']} of {lineage_output['total']}")
        lines.append(f"vintage published to s3://{vintage_output['bucket']}/{vintage_output['key']}")
        lines.append(f"reconcile_checksum record count matches consolidate: "
                      f"{reconcile_output['record_count'] == consolidate_output['record_count']}")

        readback = store.client.get_object(Bucket=vintage_output["bucket"], Key=vintage_output["key"])
        readback_payload = json.loads(readback["Body"].read().decode("utf-8"))
        lines.append(f"vintage read back from S3 matches what was written: "
                      f"{readback_payload['record_count'] == consolidate_output['record_count']}")

    output = "\n".join(lines)
    print(output)

    docs_dir = Path(__file__).resolve().parent.parent / "docs"
    docs_dir.mkdir(exist_ok=True)
    (docs_dir / "daily_pipeline_output.txt").write_text(output + "\n", encoding="utf-8")


if __name__ == "__main__":
    main()
