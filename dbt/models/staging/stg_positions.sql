select
    fund_id,
    instrument_id,
    position_date,
    quantity,
    market_value,
    local_currency,
    source_file,
    source_row
from {{ source('raw', 'positions') }}
