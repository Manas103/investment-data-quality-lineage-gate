-- Rule: price_currency_mismatch (category: pricing)
-- A pricing row whose currency disagrees with the instrument's security
-- master currency.
{{ config(store_failures=true, tags=['rule', 'pricing']) }}

select
    'price_currency_mismatch' as rule_name,
    'pricing' as rule_category,
    p.source_file,
    p.source_row,
    'currency' as column_name,
    'pricing currency ' || p.currency || ' does not match security master currency ' || sm.currency
        as detail,
    to_jsonb(p) as record_snapshot
from {{ ref('stg_pricing') }} p
join {{ ref('stg_security_master') }} sm on sm.instrument_id = p.instrument_id
where p.currency is distinct from sm.currency
