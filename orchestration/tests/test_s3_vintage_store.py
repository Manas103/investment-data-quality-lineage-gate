from moto import mock_aws

from dagpipeline.s3_vintage_store import S3VintageStore


@mock_aws
def test_write_and_read_round_trip_exactly():
    store = S3VintageStore()
    payload = {"record_count": 42, "as_of_date": "2026-10-05"}
    key = store.write_vintage("equity_us", "2026-10-05", payload)
    read_back = store.read_vintage(key)
    assert read_back == payload


@mock_aws
def test_a_second_delivery_for_the_same_schema_and_date_never_overwrites_the_first():
    store = S3VintageStore()
    key_1 = store.write_vintage("equity_us", "2026-10-05", {"delivery": "first", "value": 1})
    key_2 = store.write_vintage("equity_us", "2026-10-05", {"delivery": "second", "value": 2})

    assert key_1.key != key_2.key
    assert store.read_vintage(key_1) == {"delivery": "first", "value": 1}
    assert store.read_vintage(key_2) == {"delivery": "second", "value": 2}

    listed = store.list_vintages("equity_us", "2026-10-05")
    assert key_1.key in listed
    assert key_2.key in listed
    assert len(listed) == 2


@mock_aws
def test_vintages_for_different_schemas_or_dates_do_not_collide():
    store = S3VintageStore()
    store.write_vintage("equity_us", "2026-10-05", {"schema": "equity_us"})
    store.write_vintage("bond_us", "2026-10-05", {"schema": "bond_us"})
    store.write_vintage("equity_us", "2026-10-06", {"schema": "equity_us", "date": "next day"})

    assert len(store.list_vintages("equity_us", "2026-10-05")) == 1
    assert len(store.list_vintages("bond_us", "2026-10-05")) == 1
    assert len(store.list_vintages("equity_us", "2026-10-06")) == 1
