-- VALUE_OUT_OF_RANGE: value1 exceeds the schema's declared upper bound.
SELECT vr.source_file, vr.source_row
FROM vendor_record vr
JOIN schema_bounds sb ON sb.schema_name = vr.schema_name
WHERE vr.value1_numeric IS NOT NULL
  AND ABS(vr.value1_numeric) > sb.value1_upper_bound;
