-- Rule: coupon_rate_out_of_range_for_bond (category: security_master)
-- A bond coupon rate (stored as a fraction) must be between 0 and 20%.
{{ config(store_failures=true, tags=['rule', 'security_master']) }}

select
    'coupon_rate_out_of_range_for_bond' as rule_name,
    'security_master' as rule_category,
    sm.source_file,
    sm.source_row,
    'coupon_rate' as column_name,
    'bond ' || sm.instrument_id || ' coupon_rate ' || sm.coupon_rate::text || ' is out of the 0-20% range' as detail,
    to_jsonb(sm) as record_snapshot
from {{ ref('stg_security_master') }} sm
where sm.asset_class = 'Bond'
  and sm.coupon_rate is not null
  and (sm.coupon_rate < 0 or sm.coupon_rate > 0.20)
