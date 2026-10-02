-- Rule: missing_maturity_date_for_bond (category: security_master)
{{ config(store_failures=true, tags=['rule', 'security_master']) }}

select
    'missing_maturity_date_for_bond' as rule_name,
    'security_master' as rule_category,
    sm.source_file,
    sm.source_row,
    'maturity_date' as column_name,
    'bond ' || sm.instrument_id || ' has no maturity_date' as detail,
    to_jsonb(sm) as record_snapshot
from {{ ref('stg_security_master') }} sm
where sm.asset_class = 'Bond'
  and sm.maturity_date is null
