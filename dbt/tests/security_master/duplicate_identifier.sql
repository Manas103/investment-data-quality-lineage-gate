-- Rule: duplicate_identifier (category: security_master)
-- Two or more instruments sharing the same cusip. Every row in the sharing
-- group is flagged, not only the second one seen.
{{ config(store_failures=true, tags=['rule', 'security_master']) }}

with dup_cusips as (
    select cusip
    from {{ ref('stg_security_master') }}
    where cusip is not null and cusip <> ''
    group by cusip
    having count(*) > 1
)
select
    'duplicate_identifier' as rule_name,
    'security_master' as rule_category,
    sm.source_file,
    sm.source_row,
    'cusip' as column_name,
    'cusip ' || sm.cusip || ' is shared by more than one instrument' as detail,
    to_jsonb(sm) as record_snapshot
from {{ ref('stg_security_master') }} sm
join dup_cusips d on d.cusip = sm.cusip
