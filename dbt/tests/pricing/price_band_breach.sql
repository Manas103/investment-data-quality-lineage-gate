-- Rule: price_band_breach (category: pricing)
-- A fat-finger detector: a price that moves more than 50% versus the same
-- instrument's immediately preceding trading-day price.
{{ config(store_failures=true, tags=['rule', 'pricing']) }}

with with_prev as (
    select
        p.*,
        lag(p.price) over (partition by p.instrument_id order by p.price_date) as prev_price
    from {{ ref('stg_pricing') }} p
    where p.price > 0
)
select
    'price_band_breach' as rule_name,
    'pricing' as rule_category,
    w.source_file,
    w.source_row,
    'price' as column_name,
    'price ' || w.price::text || ' moved more than 50% from prior session price ' ||
        w.prev_price::text as detail,
    to_jsonb(w) as record_snapshot
from with_prev w
where w.prev_price is not null
  and abs(w.price / w.prev_price - 1) > 0.5
