-- Rule: missing_sector_for_equity (category: security_master)
{{ config(store_failures=true, tags=['rule', 'security_master']) }}

select
    'missing_sector_for_equity' as rule_name,
    'security_master' as rule_category,
    sm.source_file,
    sm.source_row,
    'sector' as column_name,
    'equity instrument ' || sm.instrument_id || ' has no sector' as detail,
    to_jsonb(sm) as record_snapshot
from {{ ref('stg_security_master') }} sm
where sm.asset_class = 'Equity'
  and (sm.sector is null or sm.sector = '')
