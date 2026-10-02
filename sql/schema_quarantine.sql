-- Quarantine and audit schema. Populated by the Java service's
-- QuarantineIngestService after each `dbt test` run, reading dbt's
-- store_failures tables and writing one quarantine_records row plus one
-- append-only audit_log row per failing record, with the owner resolved
-- from owner_assignments by rule_category.
--
-- Run once per database: psql -U idqg -d idqg -f sql/schema_quarantine.sql

drop schema if exists quarantine cascade;
create schema quarantine;

create table quarantine.owner_assignments (
    rule_category text primary key,
    owner_name    text not null
);

insert into quarantine.owner_assignments (rule_category, owner_name) values
    ('pricing',          'Pricing Operations - D. Alvarez'),
    ('security_master',  'Security Master - R. Chen'),
    ('position_fund',    'Fund Accounting - T. Osei');

create table quarantine.quarantine_records (
    quarantine_id   uuid primary key,
    rule_name       text not null,
    rule_category   text not null,
    owner_name      text not null,
    source_file     text not null,
    source_row      integer not null,
    column_name     text not null,
    detail          text,
    record_snapshot jsonb not null,
    detected_at     timestamptz not null default now(),
    run_id          text not null
);

create index idx_quarantine_owner on quarantine.quarantine_records (owner_name);
create index idx_quarantine_rule on quarantine.quarantine_records (rule_name);
create index idx_quarantine_lineage on quarantine.quarantine_records (source_file, source_row);

-- Append-only: no update or delete is ever issued against this table by the
-- service. Replaying a quarantine_id means re-running the rule predicate
-- against record_snapshot and asserting the same decision, not reading
-- quarantine_records back (see AuditReplayService).
create table quarantine.audit_log (
    audit_id        bigserial primary key,
    quarantine_id   uuid not null,
    rule_name       text not null,
    record_snapshot jsonb not null,
    decision        text not null,
    run_id          text not null,
    created_at      timestamptz not null default now()
);

create index idx_audit_quarantine_id on quarantine.audit_log (quarantine_id);
