-- Rule: missing_currency_code (category: security_master)
{{ config(store_failures=true, tags=['rule', 'security_master']) }}

select
    'missing_currency_code' as rule_name,
    'security_master' as rule_category,
    sm.source_file,
    sm.source_row,
    'currency' as column_name,
    'instrument ' || sm.instrument_id || ' has no currency code' as detail,
    to_jsonb(sm) as record_snapshot
from {{ ref('stg_security_master') }} sm
where sm.currency is null or sm.currency = ''
