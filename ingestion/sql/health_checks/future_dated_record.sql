-- FUTURE_DATED_RECORD: as_of_date is after the run date.
SELECT source_file, source_row
FROM vendor_record
WHERE as_of_date <> ''
  AND CAST(as_of_date AS DATE) > CAST(? AS DATE);
