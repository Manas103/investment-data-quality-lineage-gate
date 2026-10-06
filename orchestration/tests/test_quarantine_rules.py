import pytest

from dagpipeline.quarantine import run_all_rules, run_single_rule
from dagpipeline.sql_rules import ALL_SQL_RULES

RULE_BY_NAME = {r.name: r for r in ALL_SQL_RULES}


def make_record(**overrides) -> dict:
    record = {
        "as_of_date": "2026-10-05",
        "instrument_id": "EQU00001",
        "value1": "100.0",
        "value2": "0.5",
        "currency": "USD",
        "source_vendor": "vendor_a",
        "schema": "equity_us",
        "source_file": "equity_us_000.csv",
        "source_row": 0,
    }
    record.update(overrides)
    return record


ROW_LEVEL_FIXTURES = {
    "missing_instrument_id": make_record(instrument_id=""),
    "missing_currency": make_record(currency=""),
    "missing_as_of_date": make_record(as_of_date=""),
    "missing_value1": make_record(value1=None),
    "missing_value2": make_record(value2=None),
    "missing_source_vendor": make_record(source_vendor=""),
    "value1_below_schema_min": make_record(value1="-50"),
    "value1_above_schema_max": make_record(value1="200000"),
    "value1_negative_when_disallowed": make_record(value1="-10"),
    "value1_gross_outlier_10x_max": make_record(value1="2000000"),
    "value2_below_zero": make_record(value2="-0.1"),
    "value2_above_one": make_record(value2="1.5"),
    "non_usd_currency": make_record(currency="EUR"),
    "unknown_source_vendor": make_record(source_vendor="vendor_x"),
    "stale_as_of_date_past": make_record(as_of_date="2026-10-01"),
    "future_dated_record": make_record(as_of_date="2026-10-10"),
    "instrument_id_wrong_prefix": make_record(instrument_id="XYZ00001"),
    "source_row_negative": make_record(source_row=-1),
    "source_file_blank": make_record(source_file=""),
    "schema_label_mismatch": make_record(source_file="bond_us_000.csv"),
    "currency_not_three_letters": make_record(currency="US"),
    "instrument_id_wrong_length": make_record(instrument_id="EQU1"),
    "value1_exactly_zero": make_record(value1="0"),
    "as_of_date_wrong_format": make_record(as_of_date="10-05-2026"),
    "value2_is_integer_valued": make_record(value2="1.0"),
}


@pytest.mark.parametrize("rule_name", sorted(ROW_LEVEL_FIXTURES.keys()))
def test_each_row_level_rule_fires_on_its_fixture(rule_name):
    rule = RULE_BY_NAME[rule_name]
    violations = run_single_rule(rule, [ROW_LEVEL_FIXTURES[rule_name]])
    assert len(violations) >= 1, f"{rule_name} did not fire on its own fixture"


def test_duplicate_instrument_within_file_fires_on_a_real_duplicate():
    rule = RULE_BY_NAME["duplicate_instrument_within_file"]
    records = [
        make_record(source_row=0, instrument_id="EQU00001"),
        make_record(source_row=1, instrument_id="EQU00001"),
    ]
    violations = run_single_rule(rule, records)
    assert len(violations) == 2


def test_clean_record_set_produces_zero_violations_across_all_row_level_rules():
    records = [make_record(source_row=i, instrument_id=f"EQU{i:05d}") for i in range(10)]
    entries = run_all_rules(records)
    aggregate_names = {"empty_file", "row_count_anomaly_low", "row_count_anomaly_high", "file_count_below_expected"}
    non_aggregate = [e for e in entries if e.rule not in aggregate_names]
    assert non_aggregate == []


def test_empty_file_rule_fires_via_empty_files_manifest_override():
    records = [make_record(source_row=0)]
    entries = run_all_rules(records, empty_files=[("equity_us", "equity_us_999.csv")])
    names = {e.rule for e in entries}
    assert "empty_file" in names


def test_row_count_anomaly_low_fires_on_an_undersized_file():
    records = [make_record(source_row=i, source_file="equity_us_low.csv") for i in range(5)]
    entries = run_all_rules(records)
    names = {e.rule for e in entries}
    assert "row_count_anomaly_low" in names


def test_row_count_anomaly_high_fires_on_an_oversized_file():
    records = [make_record(source_row=i, source_file="equity_us_high.csv") for i in range(20)]
    entries = run_all_rules(records)
    names = {e.rule for e in entries}
    assert "row_count_anomaly_high" in names


def test_file_count_below_expected_fires_when_a_schema_is_undersupplied():
    records = [make_record(source_row=0, source_file=f"equity_us_{i:03d}.csv") for i in range(2)]
    entries = run_all_rules(records)
    names = {e.rule for e in entries}
    assert "file_count_below_expected" in names


def test_every_fixture_name_is_a_real_rule_in_all_sql_rules():
    fixture_rule_names = set(ROW_LEVEL_FIXTURES.keys()) | {"duplicate_instrument_within_file"}
    aggregate_names = {"empty_file", "row_count_anomaly_low", "row_count_anomaly_high", "file_count_below_expected"}
    all_rule_names = {r.name for r in ALL_SQL_RULES}
    assert fixture_rule_names | aggregate_names == all_rule_names
