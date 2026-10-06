import json

import pytest
from moto import mock_aws

from dagpipeline.dag import run_dag
from dagpipeline.pipeline import build_task_specs
from dagpipeline.s3_vintage_store import S3VintageStore
from dagpipeline.schemas import SCHEMAS
from dagpipeline.vendor_files import generate_one_days_vendor_files


@pytest.fixture
def vendor_dir(tmp_path):
    out = tmp_path / "vendor"
    generate_one_days_vendor_files(out)
    return out


@mock_aws
def test_the_pipeline_builds_exactly_55_tasks(vendor_dir, tmp_path):
    store = S3VintageStore()
    tasks = build_task_specs(vendor_dir, store)
    assert len(tasks) == 55
    assert len({t.task_id for t in tasks}) == 55


@mock_aws
def test_full_pipeline_run_has_zero_quarantine_violations_on_the_clean_corpus(vendor_dir, tmp_path):
    store = S3VintageStore()
    tasks = build_task_specs(vendor_dir, store)
    run_dir = tmp_path / "run"
    result = run_dag(run_dir, tasks)

    assert len(result.executed) == 55
    quarantine = json.loads((run_dir / "outputs" / "quarantine.json").read_text())
    assert quarantine["count"] == 0

    consolidate = json.loads((run_dir / "outputs" / "consolidate.json").read_text())
    assert consolidate["record_count"] == 15 * 44 * 35
    assert consolidate["file_count"] == 44 * 35
    assert consolidate["file_count"] >= 1500


@mock_aws
def test_full_pipeline_crash_and_resume_matches_an_uninterrupted_run(vendor_dir, tmp_path):
    store_clean = S3VintageStore()
    tasks_clean = build_task_specs(vendor_dir, store_clean)
    clean_run_dir = tmp_path / "clean_run"
    run_dag(clean_run_dir, tasks_clean)
    clean_quarantine = json.loads((clean_run_dir / "outputs" / "quarantine.json").read_text())
    clean_consolidate = json.loads((clean_run_dir / "outputs" / "consolidate.json").read_text())

    store_resumed = S3VintageStore()
    tasks_resumed = build_task_specs(vendor_dir, store_resumed)
    resumed_run_dir = tmp_path / "resumed_run"
    with pytest.raises(RuntimeError, match="simulated crash after task validate_group_4"):
        run_dag(resumed_run_dir, tasks_resumed, crash_after="validate_group_4")

    checkpoints_mid_crash = json.loads((resumed_run_dir / "checkpoints.json").read_text())
    assert "validate_group_4" in checkpoints_mid_crash
    assert "quarantine" not in checkpoints_mid_crash
    assert "end" not in checkpoints_mid_crash

    tasks_resumed_again = build_task_specs(vendor_dir, store_resumed)
    final_result = run_dag(resumed_run_dir, tasks_resumed_again)
    assert "validate_group_4" in final_result.skipped
    assert "end" in final_result.executed

    resumed_quarantine = json.loads((resumed_run_dir / "outputs" / "quarantine.json").read_text())
    resumed_consolidate = json.loads((resumed_run_dir / "outputs" / "consolidate.json").read_text())

    assert resumed_quarantine["count"] == clean_quarantine["count"]
    assert resumed_consolidate["record_count"] == clean_consolidate["record_count"]
    assert resumed_consolidate["file_count"] == clean_consolidate["file_count"]


@mock_aws
def test_full_pipeline_publishes_a_readable_s3_vintage(vendor_dir, tmp_path):
    store = S3VintageStore()
    tasks = build_task_specs(vendor_dir, store)
    run_dir = tmp_path / "run"
    run_dag(run_dir, tasks)

    vintage_output = json.loads((run_dir / "outputs" / "vintage_publish_s3.json").read_text())
    read_back = store.client.get_object(Bucket=vintage_output["bucket"], Key=vintage_output["key"])
    payload = json.loads(read_back["Body"].read().decode("utf-8"))

    consolidate = json.loads((run_dir / "outputs" / "consolidate.json").read_text())
    assert payload["record_count"] == consolidate["record_count"]
