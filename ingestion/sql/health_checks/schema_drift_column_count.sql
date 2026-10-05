-- SCHEMA_DRIFT_COLUMN_COUNT: the file's own header had a different column count than its schema declares.
SELECT source_file, source_row
FROM vendor_record
WHERE actual_column_count <> declared_column_count;
