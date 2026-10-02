-- Rule: negative_price (category: pricing)
{{ config(store_failures=true, tags=['rule', 'pricing']) }}

select
    'negative_price' as rule_name,
    'pricing' as rule_category,
    p.source_file,
    p.source_row,
    'price' as column_name,
    'price ' || p.price::text || ' is negative' as detail,
    to_jsonb(p) as record_snapshot
from {{ ref('stg_pricing') }} p
where p.price < 0
