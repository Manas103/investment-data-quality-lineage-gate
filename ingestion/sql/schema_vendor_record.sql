CREATE TABLE vendor_record (
    source_file VARCHAR NOT NULL,
    source_row INT NOT NULL,
    vendor VARCHAR NOT NULL,
    schema_name VARCHAR NOT NULL,
    instrument_id VARCHAR NOT NULL,
    as_of_date VARCHAR NOT NULL,
    received_at VARCHAR NOT NULL,
    currency VARCHAR NOT NULL,
    value1 VARCHAR NOT NULL,
    value2 VARCHAR NOT NULL,
    value1_numeric DOUBLE, -- NULL when value1 is not parseable as a number (the TYPE_MISMATCH case)
    declared_column_count INT NOT NULL,
    actual_column_count INT NOT NULL,
    missing_required_column BOOLEAN NOT NULL
);

CREATE TABLE instrument_master (
    instrument_id VARCHAR PRIMARY KEY
);

CREATE TABLE schema_bounds (
    schema_name VARCHAR PRIMARY KEY,
    value1_upper_bound DOUBLE NOT NULL,
    allow_negative_value1 BOOLEAN NOT NULL
);
