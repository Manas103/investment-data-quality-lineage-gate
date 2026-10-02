-- Rule: holdings_to_nav_tie_out (category: position_fund)
-- Sum of position market values for a fund/date must tie to the fund's
-- reported NAV within 0.5% relative tolerance. The anchor row is the
-- funds.csv row for that fund/date, since the disagreement is a fund-level
-- fact, not any single position's fault.
{{ config(store_failures=true, tags=['rule', 'position_fund']) }}

with true_sums as (
    select fund_id, position_date, sum(market_value) as true_total
    from {{ ref('stg_positions') }}
    group by fund_id, position_date
)
select
    'holdings_to_nav_tie_out' as rule_name,
    'position_fund' as rule_category,
    f.source_file,
    f.source_row,
    'reported_nav' as column_name,
    'fund ' || f.fund_id || ' on ' || f.nav_date::text || ' reported_nav ' || f.reported_nav::text ||
        ' vs true holdings total ' || t.true_total::text as detail,
    to_jsonb(f) as record_snapshot
from {{ ref('stg_funds') }} f
join true_sums t on t.fund_id = f.fund_id and t.position_date = f.nav_date
where f.reported_nav is null
   or f.reported_nav = 0
   or abs(t.true_total - f.reported_nav) / abs(nullif(f.reported_nav, 0)) > 0.005
