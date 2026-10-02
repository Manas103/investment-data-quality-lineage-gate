-- Rule: negative_position_quantity (category: position_fund)
-- A negative quantity is valid only for a Cash sweep line (an overdraft);
-- any other asset class with negative quantity is flagged.
{{ config(store_failures=true, tags=['rule', 'position_fund']) }}

select
    'negative_position_quantity' as rule_name,
    'position_fund' as rule_category,
    p.source_file,
    p.source_row,
    'quantity' as column_name,
    'position in ' || p.instrument_id || ' has negative quantity ' || p.quantity::text as detail,
    to_jsonb(p) as record_snapshot
from {{ ref('stg_positions') }} p
left join {{ ref('stg_security_master') }} sm on sm.instrument_id = p.instrument_id
where p.quantity < 0
  and coalesce(sm.asset_class, '') <> 'Cash'
