-- Raw landing tables for the daily security-master, pricing, position and
-- funds files, plus the small reference tables. Loaded by
-- scripts/load_to_postgres.sh via psql \copy from the generator's CSV
-- output. dbt's staging models read these via source().
--
-- Run once per database: psql -U idqg -d idqg -f sql/schema_raw.sql

drop schema if exists raw cascade;
create schema raw;

create table raw.security_master (
    instrument_id   text not null,
    cusip           text,
    isin            text,
    instrument_name text,
    asset_class     text,
    currency        text,
    country         text,
    sector          text,
    benchmark_id    text,
    maturity_date   date,
    coupon_rate     numeric,
    source_file     text not null,
    source_row      integer not null
);

create table raw.pricing (
    instrument_id text not null,
    price_date    date not null,
    price         numeric,
    currency      text,
    source_file   text not null,
    source_row    integer not null
);

create table raw.positions (
    fund_id        text not null,
    instrument_id  text not null,
    position_date  date not null,
    quantity       numeric,
    market_value   numeric,
    local_currency text,
    source_file    text not null,
    source_row     integer not null
);

create table raw.funds (
    fund_id       text not null,
    fund_name     text,
    nav_date      date not null,
    reported_nav  numeric,
    source_file   text not null,
    source_row    integer not null
);

create table raw.fx_rates (
    currency_code    text not null,
    rate_date        date not null,
    fx_rate_to_base  numeric,
    source_file      text not null,
    source_row       integer not null
);

create table raw.ref_benchmarks (
    benchmark_id   text not null,
    benchmark_name text
);

create table raw.ref_currency (
    currency_code text not null,
    currency_name text
);

create index idx_pricing_instrument_date on raw.pricing (instrument_id, price_date);
create index idx_positions_fund_date on raw.positions (fund_id, position_date);
create index idx_positions_instrument on raw.positions (instrument_id);
create index idx_fx_rates_ccy_date on raw.fx_rates (currency_code, rate_date);
