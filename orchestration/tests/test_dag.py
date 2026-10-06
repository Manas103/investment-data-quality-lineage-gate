import pytest

from dagpipeline.dag import TaskContext, TaskSpec, run_dag


def _make_counting_tasks(call_log: list[str]) -> list[TaskSpec]:
    def make_fn(task_id: str):
        def _fn(ctx: TaskContext) -> dict:
            call_log.append(task_id)
            return {"task_id": task_id, "upstream": list(ctx.inputs.keys())}
        return _fn

    return [
        TaskSpec("a", [], make_fn("a")),
        TaskSpec("b", ["a"], make_fn("b")),
        TaskSpec("c", ["a"], make_fn("c")),
        TaskSpec("d", ["b", "c"], make_fn("d")),
    ]


def test_all_tasks_execute_in_dependency_order_on_a_clean_run(tmp_path):
    call_log: list[str] = []
    tasks = _make_counting_tasks(call_log)
    result = run_dag(tmp_path, tasks)

    assert result.executed == ["a", "b", "c", "d"]
    assert result.skipped == []
    assert call_log.index("a") < call_log.index("b")
    assert call_log.index("a") < call_log.index("c")
    assert call_log.index("b") < call_log.index("d")
    assert call_log.index("c") < call_log.index("d")


def test_retrying_a_fully_completed_run_executes_nothing_again(tmp_path):
    call_log: list[str] = []
    tasks = _make_counting_tasks(call_log)
    run_dag(tmp_path, tasks)
    call_log.clear()

    result = run_dag(tmp_path, tasks)

    assert result.executed == []
    assert sorted(result.skipped) == ["a", "b", "c", "d"]
    assert call_log == []


def test_mid_run_crash_then_resume_executes_only_the_remaining_tasks(tmp_path):
    call_log: list[str] = []
    tasks = _make_counting_tasks(call_log)

    with pytest.raises(RuntimeError, match="simulated crash after task b"):
        run_dag(tmp_path, tasks, crash_after="b")

    assert call_log == ["a", "b"]
    call_log.clear()

    result = run_dag(tmp_path, tasks)

    assert call_log == ["c", "d"]
    assert sorted(result.skipped) == ["a", "b"]
    assert sorted(result.executed) == ["c", "d"]


def test_resumed_run_produces_identical_final_state_to_an_uninterrupted_run(tmp_path):
    clean_log: list[str] = []
    clean_dir = tmp_path / "clean"
    run_dag(clean_dir, _make_counting_tasks(clean_log))
    clean_output = (clean_dir / "outputs" / "d.json").read_text()

    resumed_log: list[str] = []
    resumed_dir = tmp_path / "resumed"
    tasks = _make_counting_tasks(resumed_log)
    with pytest.raises(RuntimeError):
        run_dag(resumed_dir, tasks, crash_after="c")
    run_dag(resumed_dir, tasks)
    resumed_output = (resumed_dir / "outputs" / "d.json").read_text()

    assert resumed_output == clean_output


def test_idempotent_retry_never_duplicates_a_checkpoint_entry(tmp_path):
    call_log: list[str] = []
    tasks = _make_counting_tasks(call_log)
    run_dag(tmp_path, tasks)
    run_dag(tmp_path, tasks)
    run_dag(tmp_path, tasks)

    import json
    checkpoints = json.loads((tmp_path / "checkpoints.json").read_text())
    assert checkpoints == {"a": "done", "b": "done", "c": "done", "d": "done"}
