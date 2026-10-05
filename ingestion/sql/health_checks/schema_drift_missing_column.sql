-- SCHEMA_DRIFT_MISSING_COLUMN: the file's own header is missing one of the schema's required columns.
SELECT source_file, source_row
FROM vendor_record
WHERE missing_required_column = TRUE;
