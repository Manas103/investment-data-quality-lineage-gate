-- Rule: future_dated_price (category: pricing)
-- A price dated after the real wall-clock date it was validated on.
{{ config(store_failures=true, tags=['rule', 'pricing']) }}

select
    'future_dated_price' as rule_name,
    'pricing' as rule_category,
    p.source_file,
    p.source_row,
    'price_date' as column_name,
    'price_date ' || p.price_date::text || ' is after the validation run date' as detail,
    to_jsonb(p) as record_snapshot
from {{ ref('stg_pricing') }} p
where p.price_date > current_date
