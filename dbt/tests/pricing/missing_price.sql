-- Rule: missing_price (category: pricing)
-- An instrument in the security master with zero pricing rows at all is
-- flagged against its own security_master record (there is no pricing row
-- to anchor to, since the defect is an absence).
{{ config(store_failures=true, tags=['rule', 'pricing']) }}

select
    'missing_price' as rule_name,
    'pricing' as rule_category,
    sm.source_file,
    sm.source_row,
    'instrument_id' as column_name,
    sm.instrument_id || ' has no pricing rows in the dataset' as detail,
    to_jsonb(sm) as record_snapshot
from {{ ref('stg_security_master') }} sm
where not exists (
    select 1 from {{ ref('stg_pricing') }} p where p.instrument_id = sm.instrument_id
)
