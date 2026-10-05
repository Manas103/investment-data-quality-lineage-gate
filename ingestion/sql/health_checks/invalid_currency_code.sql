-- INVALID_CURRENCY_CODE: currency is not one of the known ISO codes.
SELECT source_file, source_row
FROM vendor_record
WHERE currency <> ''
  AND currency NOT IN ('USD', 'EUR', 'GBP', 'JPY', 'CHF');
