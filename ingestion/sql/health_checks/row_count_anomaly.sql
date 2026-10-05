-- ROW_COUNT_ANOMALY: a file's row count deviates more than 30% from its schema's median file
-- size, where the median is computed only over files with at least 50 rows (see README Findings
-- for why the baseline population excludes the deliberately tiny fixture files).
SELECT vr.source_file, vr.source_row
FROM vendor_record vr
JOIN (
    SELECT source_file, schema_name, COUNT(*) AS row_count
    FROM vendor_record
    GROUP BY source_file, schema_name
) fc ON fc.source_file = vr.source_file
JOIN (
    SELECT DISTINCT schema_name,
           PERCENTILE_CONT(0.5) WITHIN GROUP (ORDER BY row_count) OVER (PARTITION BY schema_name) AS median_rows
    FROM (
        SELECT source_file, schema_name, COUNT(*) AS row_count
        FROM vendor_record
        GROUP BY source_file, schema_name
        HAVING COUNT(*) >= 50
    ) baseline
) med ON med.schema_name = vr.schema_name
WHERE med.median_rows > 0
  AND ABS(fc.row_count - med.median_rows) / med.median_rows > 0.3;
