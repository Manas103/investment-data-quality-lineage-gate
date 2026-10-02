-- Rule: fx_rate_missing_for_nonbase_currency (category: position_fund)
-- A position reported in a non-USD local_currency must have a matching
-- fx_rates row for that currency and date.
{{ config(store_failures=true, tags=['rule', 'position_fund']) }}

select
    'fx_rate_missing_for_nonbase_currency' as rule_name,
    'position_fund' as rule_category,
    p.source_file,
    p.source_row,
    'local_currency' as column_name,
    'no fx rate found for ' || p.local_currency || ' on ' || p.position_date::text as detail,
    to_jsonb(p) as record_snapshot
from {{ ref('stg_positions') }} p
where p.local_currency is not null
  and p.local_currency <> 'USD'
  and not exists (
      select 1 from {{ source('raw', 'fx_rates') }} fx
      where fx.currency_code = p.local_currency and fx.rate_date = p.position_date
  )
