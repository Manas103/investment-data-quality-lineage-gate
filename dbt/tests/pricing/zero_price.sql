-- Rule: zero_price (category: pricing)
{{ config(store_failures=true, tags=['rule', 'pricing']) }}

select
    'zero_price' as rule_name,
    'pricing' as rule_category,
    p.source_file,
    p.source_row,
    'price' as column_name,
    'price is exactly zero' as detail,
    to_jsonb(p) as record_snapshot
from {{ ref('stg_pricing') }} p
where p.price = 0
