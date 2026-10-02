-- Rule: invalid_currency_code (category: security_master)
-- A non-empty currency code that does not resolve in the ISO currency
-- reference table (disjoint from missing_currency_code, which covers the
-- null/empty case).
{{ config(store_failures=true, tags=['rule', 'security_master']) }}

select
    'invalid_currency_code' as rule_name,
    'security_master' as rule_category,
    sm.source_file,
    sm.source_row,
    'currency' as column_name,
    'currency code ' || sm.currency || ' is not a recognized ISO code' as detail,
    to_jsonb(sm) as record_snapshot
from {{ ref('stg_security_master') }} sm
where sm.currency is not null
  and sm.currency <> ''
  and not exists (
      select 1 from {{ source('raw', 'ref_currency') }} c where c.currency_code = sm.currency
  )
