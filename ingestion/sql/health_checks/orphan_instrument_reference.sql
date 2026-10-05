-- ORPHAN_INSTRUMENT_REFERENCE: instrument_id is not present in the reference instrument master.
SELECT vr.source_file, vr.source_row
FROM vendor_record vr
LEFT JOIN instrument_master im ON im.instrument_id = vr.instrument_id
WHERE vr.instrument_id <> '' AND im.instrument_id IS NULL;
