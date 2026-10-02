-- Rule: missing_asset_class (category: security_master)
{{ config(store_failures=true, tags=['rule', 'security_master']) }}

select
    'missing_asset_class' as rule_name,
    'security_master' as rule_category,
    sm.source_file,
    sm.source_row,
    'asset_class' as column_name,
    'instrument ' || sm.instrument_id || ' has no asset_class' as detail,
    to_jsonb(sm) as record_snapshot
from {{ ref('stg_security_master') }} sm
where sm.asset_class is null or sm.asset_class = ''
