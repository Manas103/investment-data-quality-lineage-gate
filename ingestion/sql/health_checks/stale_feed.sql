-- STALE_FEED: as_of_date is more than 5 days behind the run date.
SELECT source_file, source_row
FROM vendor_record
WHERE as_of_date <> ''
  AND DATEDIFF('DAY', CAST(as_of_date AS DATE), CAST(? AS DATE)) > 5;
