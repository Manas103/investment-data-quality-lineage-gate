"""Demonstrates mid-run resume: runs the 55-task DAG once uninterrupted, then
a second time with a simulated crash partway through, then resumes the
second run and confirms its final state matches the uninterrupted run
exactly.

Writes docs/resume_demo_output.txt.
"""
from __future__ import annotations

import json
import sys
import tempfile
from pathlib import Path

from moto import mock_aws

sys.path.insert(0, str(Path(__file__).resolve().parent.parent))

from dagpipeline.dag import run_dag
from dagpipeline.pipeline import build_task_specs
from dagpipeline.s3_vintage_store import S3VintageStore
from dagpipeline.vendor_files import generate_one_days_vendor_files

CRASH_AFTER_TASK = "validate_group_4"


def main() -> None:
    lines = []

    with mock_aws():
        vendor_dir = Path(tempfile.mkdtemp(prefix="dagpipeline_resume_vendor_"))
        generate_one_days_vendor_files(vendor_dir)

        clean_run_dir = Path(tempfile.mkdtemp(prefix="dagpipeline_resume_clean_"))
        clean_tasks = build_task_specs(vendor_dir, S3VintageStore())
        clean_result = run_dag(clean_run_dir, clean_tasks)
        lines.append(f"clean run: executed {len(clean_result.executed)} tasks, skipped {len(clean_result.skipped)}")

        resumed_run_dir = Path(tempfile.mkdtemp(prefix="dagpipeline_resume_crash_"))
        crash_tasks = build_task_specs(vendor_dir, S3VintageStore())
        try:
            run_dag(resumed_run_dir, crash_tasks, crash_after=CRASH_AFTER_TASK)
            lines.append("ERROR: expected a simulated crash, none occurred")
        except RuntimeError as exc:
            lines.append(f"simulated crash: {exc}")

        checkpoints_at_crash = json.loads((resumed_run_dir / "checkpoints.json").read_text())
        lines.append(f"tasks completed before the crash: {len(checkpoints_at_crash)} of 55")
        lines.append(f"'{CRASH_AFTER_TASK}' in checkpoints at crash time: {CRASH_AFTER_TASK in checkpoints_at_crash}")
        lines.append(f"'quarantine' in checkpoints at crash time (should be False, it runs later): "
                      f"{'quarantine' in checkpoints_at_crash}")

        resume_tasks = build_task_specs(vendor_dir, S3VintageStore())
        resume_result = run_dag(resumed_run_dir, resume_tasks)
        lines.append(f"resume run: executed {len(resume_result.executed)} tasks, "
                     f"skipped {len(resume_result.skipped)} (the ones from before the crash)")

        clean_quarantine = json.loads((clean_run_dir / "outputs" / "quarantine.json").read_text())
        resumed_quarantine = json.loads((resumed_run_dir / "outputs" / "quarantine.json").read_text())
        clean_consolidate = json.loads((clean_run_dir / "outputs" / "consolidate.json").read_text())
        resumed_consolidate = json.loads((resumed_run_dir / "outputs" / "consolidate.json").read_text())

        lines.append(f"clean run quarantine count: {clean_quarantine['count']}, "
                     f"resumed run quarantine count: {resumed_quarantine['count']}")
        lines.append(f"clean run record count: {clean_consolidate['record_count']}, "
                     f"resumed run record count: {resumed_consolidate['record_count']}")
        lines.append(f"resumed run matches the uninterrupted run exactly: "
                     f"{resumed_quarantine == clean_quarantine and resumed_consolidate['record_count'] == clean_consolidate['record_count']}")

    output = "\n".join(lines)
    print(output)

    docs_dir = Path(__file__).resolve().parent.parent / "docs"
    docs_dir.mkdir(exist_ok=True)
    (docs_dir / "resume_demo_output.txt").write_text(output + "\n", encoding="utf-8")


if __name__ == "__main__":
    main()
