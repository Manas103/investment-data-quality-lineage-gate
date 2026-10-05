-- TYPE_MISMATCH: value1 is present but not numeric.
SELECT source_file, source_row
FROM vendor_record
WHERE value1 <> '' AND value1_numeric IS NULL;
