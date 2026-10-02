select
    instrument_id,
    price_date,
    price,
    currency,
    source_file,
    source_row
from {{ source('raw', 'pricing') }}
