-- Rule: duplicate_position_row (category: position_fund)
-- The same (fund_id, instrument_id, position_date) must appear at most once.
{{ config(store_failures=true, tags=['rule', 'position_fund']) }}

with dups as (
    select fund_id, instrument_id, position_date
    from {{ ref('stg_positions') }}
    group by fund_id, instrument_id, position_date
    having count(*) > 1
)
select
    'duplicate_position_row' as rule_name,
    'position_fund' as rule_category,
    p.source_file,
    p.source_row,
    'position_date' as column_name,
    '(' || p.fund_id || ', ' || p.instrument_id || ', ' || p.position_date::text ||
        ') appears more than once' as detail,
    to_jsonb(p) as record_snapshot
from {{ ref('stg_positions') }} p
join dups d on d.fund_id = p.fund_id and d.instrument_id = p.instrument_id and d.position_date = p.position_date
