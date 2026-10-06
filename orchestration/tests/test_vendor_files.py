from dagpipeline.schemas import SCHEMAS
from dagpipeline.vendor_files import FILES_PER_SCHEMA, ROWS_PER_FILE, generate_one_days_vendor_files, read_vendor_file


def test_generates_at_least_1500_files_across_35_schemas(tmp_path):
    files = generate_one_days_vendor_files(tmp_path)
    assert len(files) == FILES_PER_SCHEMA * len(SCHEMAS)
    assert len(files) >= 1500

    schemas_seen = {f.name.rsplit("_", 1)[0] for f in files}
    assert schemas_seen == {s.name for s in SCHEMAS}


def test_each_file_has_the_declared_row_count_and_lineage(tmp_path):
    files = generate_one_days_vendor_files(tmp_path)
    sample = files[0]
    schema_name = sample.name.rsplit("_", 1)[0]
    records = read_vendor_file(sample, schema_name)

    assert len(records) == ROWS_PER_FILE
    for i, record in enumerate(records):
        assert record["source_row"] == i
        assert record["source_file"] == sample.name
        assert record["schema"] == schema_name


def test_instrument_ids_within_one_file_are_unique(tmp_path):
    files = generate_one_days_vendor_files(tmp_path)
    for f in files[:10]:
        schema_name = f.name.rsplit("_", 1)[0]
        records = read_vendor_file(f, schema_name)
        instrument_ids = [r["instrument_id"] for r in records]
        assert len(instrument_ids) == len(set(instrument_ids))
