-- NEGATIVE_VALUE: value1 is negative for a schema that does not allow it.
SELECT vr.source_file, vr.source_row
FROM vendor_record vr
JOIN schema_bounds sb ON sb.schema_name = vr.schema_name
WHERE vr.value1_numeric IS NOT NULL
  AND vr.value1_numeric < 0
  AND sb.allow_negative_value1 = FALSE;
