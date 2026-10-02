-- Rule: stale_price (category: pricing)
-- A row is flagged when it is an instrument's single most recent price and
-- that price is more than 5 calendar days older than the newest price date
-- present anywhere in the dataset.
{{ config(store_failures=true, tags=['rule', 'pricing']) }}

with latest_per_instrument as (
    select instrument_id, max(price_date) as latest_date
    from {{ ref('stg_pricing') }}
    group by instrument_id
),
dataset_max as (
    select max(price_date) as max_date from {{ ref('stg_pricing') }}
)
select
    'stale_price' as rule_name,
    'pricing' as rule_category,
    p.source_file,
    p.source_row,
    'price_date' as column_name,
    'latest price for ' || p.instrument_id || ' is ' || l.latest_date::text ||
        ', more than 5 days older than dataset max ' || d.max_date::text as detail,
    to_jsonb(p) as record_snapshot
from {{ ref('stg_pricing') }} p
join latest_per_instrument l
    on p.instrument_id = l.instrument_id and p.price_date = l.latest_date
cross join dataset_max d
where l.latest_date < d.max_date - interval '5 days'
