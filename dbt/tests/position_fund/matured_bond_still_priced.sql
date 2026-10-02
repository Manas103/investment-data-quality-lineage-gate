-- Rule: matured_bond_still_priced (category: position_fund)
-- A pricing row for a bond dated after that bond's own maturity_date.
{{ config(store_failures=true, tags=['rule', 'position_fund']) }}

select
    'matured_bond_still_priced' as rule_name,
    'position_fund' as rule_category,
    p.source_file,
    p.source_row,
    'price_date' as column_name,
    'bond ' || p.instrument_id || ' priced on ' || p.price_date::text ||
        ' after its maturity_date ' || sm.maturity_date::text as detail,
    to_jsonb(p) as record_snapshot
from {{ ref('stg_pricing') }} p
join {{ ref('stg_security_master') }} sm on sm.instrument_id = p.instrument_id
where sm.asset_class = 'Bond'
  and sm.maturity_date is not null
  and p.price_date > sm.maturity_date
