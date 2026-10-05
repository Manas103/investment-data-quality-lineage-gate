-- MISSING_REQUIRED_FIELD: instrument_id or as_of_date is blank.
SELECT source_file, source_row
FROM vendor_record
WHERE TRIM(instrument_id) = '' OR TRIM(as_of_date) = '';
