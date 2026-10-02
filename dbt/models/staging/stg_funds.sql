select
    fund_id,
    fund_name,
    nav_date,
    reported_nav,
    source_file,
    source_row
from {{ source('raw', 'funds') }}
