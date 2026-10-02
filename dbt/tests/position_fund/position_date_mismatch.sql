-- Rule: position_date_mismatch (category: position_fund)
-- A position dated on a day with no matching pricing row for that same
-- instrument, meaning the position cannot be priced against the feed.
{{ config(store_failures=true, tags=['rule', 'position_fund']) }}

select
    'position_date_mismatch' as rule_name,
    'position_fund' as rule_category,
    p.source_file,
    p.source_row,
    'position_date' as column_name,
    'no pricing row for instrument ' || p.instrument_id || ' on position_date ' || p.position_date::text as detail,
    to_jsonb(p) as record_snapshot
from {{ ref('stg_positions') }} p
where not exists (
    select 1 from {{ ref('stg_pricing') }} pr
    where pr.instrument_id = p.instrument_id and pr.price_date = p.position_date
)
