-- Rule: missing_benchmark (category: security_master)
-- Every Equity or ETF instrument must carry a benchmark_id that resolves in
-- the benchmark reference table.
{{ config(store_failures=true, tags=['rule', 'security_master']) }}

select
    'missing_benchmark' as rule_name,
    'security_master' as rule_category,
    sm.source_file,
    sm.source_row,
    'benchmark_id' as column_name,
    sm.asset_class || ' instrument ' || sm.instrument_id || ' has no resolvable benchmark_id' as detail,
    to_jsonb(sm) as record_snapshot
from {{ ref('stg_security_master') }} sm
where sm.asset_class in ('Equity', 'ETF')
  and (
    sm.benchmark_id is null
    or sm.benchmark_id = ''
    or not exists (
        select 1 from {{ source('raw', 'ref_benchmarks') }} b where b.benchmark_id = sm.benchmark_id
    )
  )
