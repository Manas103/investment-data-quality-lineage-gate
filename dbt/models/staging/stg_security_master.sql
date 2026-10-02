select
    instrument_id,
    cusip,
    isin,
    instrument_name,
    asset_class,
    currency,
    country,
    sector,
    benchmark_id,
    maturity_date,
    coupon_rate,
    source_file,
    source_row
from {{ source('raw', 'security_master') }}
