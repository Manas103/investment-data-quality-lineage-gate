-- Rule: orphan_position (category: position_fund)
{{ config(store_failures=true, tags=['rule', 'position_fund']) }}

select
    'orphan_position' as rule_name,
    'position_fund' as rule_category,
    p.source_file,
    p.source_row,
    'instrument_id' as column_name,
    'position references instrument_id ' || p.instrument_id || ' not present in security master' as detail,
    to_jsonb(p) as record_snapshot
from {{ ref('stg_positions') }} p
where not exists (
    select 1 from {{ ref('stg_security_master') }} sm where sm.instrument_id = p.instrument_id
)
