-- DUPLICATE_ROW: (vendor, schema, instrument, as_of_date) appears more than once in the vintage.
SELECT vr.source_file, vr.source_row
FROM vendor_record vr
JOIN (
    SELECT vendor, schema_name, instrument_id, as_of_date, COUNT(*) AS n
    FROM vendor_record
    GROUP BY vendor, schema_name, instrument_id, as_of_date
    HAVING COUNT(*) > 1
) dup
  ON dup.vendor = vr.vendor AND dup.schema_name = vr.schema_name
 AND dup.instrument_id = vr.instrument_id AND dup.as_of_date = vr.as_of_date;
