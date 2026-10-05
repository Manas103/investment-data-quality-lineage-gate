-- CROSS_SOURCE_DISAGREEMENT: two vendors report value1 for the same (schema, instrument,
-- as_of_date) that differ by more than 15% of the larger value.
SELECT vr.source_file, vr.source_row
FROM vendor_record vr
JOIN (
    SELECT schema_name, instrument_id, as_of_date,
           MAX(value1_numeric) AS max_value,
           MIN(value1_numeric) AS min_value,
           COUNT(DISTINCT vendor) AS vendor_count
    FROM vendor_record
    WHERE value1_numeric IS NOT NULL
    GROUP BY schema_name, instrument_id, as_of_date
) grp
  ON grp.schema_name = vr.schema_name
 AND grp.instrument_id = vr.instrument_id
 AND grp.as_of_date = vr.as_of_date
WHERE grp.vendor_count > 1
  AND grp.max_value > 0
  AND (grp.max_value - grp.min_value) / grp.max_value > 0.15;
