#!/bin/bash
# Loads a generated data corpus (security_master.csv, pricing.csv,
# positions.csv, funds.csv, fx_rates.csv, ref_benchmarks.csv, ref_currency.csv)
# into the raw schema via \copy. Truncates first so repeat runs are clean.
#
# Usage: ./load_to_postgres.sh /path/to/corpus_dir
set -euo pipefail
CORPUS_DIR="$1"
export PGPASSWORD=idqg_dev_pw

psql -h localhost -U idqg -d idqg -v ON_ERROR_STOP=1 <<SQL
truncate table raw.security_master, raw.pricing, raw.positions, raw.funds, raw.fx_rates, raw.ref_benchmarks, raw.ref_currency;
SQL

psql -h localhost -U idqg -d idqg -v ON_ERROR_STOP=1 -c "\copy raw.security_master (instrument_id, cusip, isin, instrument_name, asset_class, currency, country, sector, benchmark_id, maturity_date, coupon_rate, source_file, source_row) from '${CORPUS_DIR}/security_master.csv' with (format csv, header true, null '')"
psql -h localhost -U idqg -d idqg -v ON_ERROR_STOP=1 -c "\copy raw.pricing (instrument_id, price_date, price, currency, source_file, source_row) from '${CORPUS_DIR}/pricing.csv' with (format csv, header true, null '')"
psql -h localhost -U idqg -d idqg -v ON_ERROR_STOP=1 -c "\copy raw.positions (fund_id, instrument_id, position_date, quantity, market_value, local_currency, source_file, source_row) from '${CORPUS_DIR}/positions.csv' with (format csv, header true, null '')"
psql -h localhost -U idqg -d idqg -v ON_ERROR_STOP=1 -c "\copy raw.funds (fund_id, fund_name, nav_date, reported_nav, source_file, source_row) from '${CORPUS_DIR}/funds.csv' with (format csv, header true, null '')"
psql -h localhost -U idqg -d idqg -v ON_ERROR_STOP=1 -c "\copy raw.fx_rates (currency_code, rate_date, fx_rate_to_base, source_file, source_row) from '${CORPUS_DIR}/fx_rates.csv' with (format csv, header true, null '')"
psql -h localhost -U idqg -d idqg -v ON_ERROR_STOP=1 -c "\copy raw.ref_benchmarks (benchmark_id, benchmark_name) from '${CORPUS_DIR}/ref_benchmarks.csv' with (format csv, header true, null '')"
psql -h localhost -U idqg -d idqg -v ON_ERROR_STOP=1 -c "\copy raw.ref_currency (currency_code, currency_name) from '${CORPUS_DIR}/ref_currency.csv' with (format csv, header true, null '')"

echo "load complete"
