#!/usr/bin/env python3
"""
Synthetic daily security-master, pricing and position file generator for a
480-instrument multi-asset fund family (Equity, Bond, ETF, Option, Cash).
Every record is synthetic; nothing here reads from or represents any real
fund, issuer or market data vendor.

Two modes:
  --mode clean    generates a purely clean corpus (no injected defects),
                   used to measure the false-quarantine rate at scale.
  --mode defects  generates the same shape of corpus with exactly 45 named
                   defects injected (see rules_reference.defect_counts_for_45),
                   used to measure the seeded-defect catch rate.

Every row in every file carries its own source_file and source_row, which is
the column-level lineage anchor the dbt tests and the Java rule oracle both
point back to.

Usage:
  python3 generate.py --mode defects --days 30  --out-dir data/defect_corpus --seed 42
  python3 generate.py --mode clean   --days 500 --out-dir data/clean_1_2m   --seed 7
"""
import argparse
import csv
import datetime
import json
import os
import random
import sys

from rules_reference import RULE_CATEGORY, defect_counts_for_45

BASE_CURRENCY = "USD"
VALID_CURRENCIES = ["USD", "EUR", "GBP", "JPY", "CAD"]
INVALID_CURRENCY_SAMPLE = "ZZZ"  # deliberately not in VALID_CURRENCIES

ASSET_CLASS_COUNTS = [
    ("Equity", 220),
    ("Bond", 150),
    ("ETF", 60),
    ("Option", 30),
    ("Cash", 20),
]
assert sum(n for _, n in ASSET_CLASS_COUNTS) == 480

SECTORS = ["Technology", "Healthcare", "Financials", "Energy", "Consumer",
           "Industrials", "Utilities", "Materials"]
BENCHMARKS = [
    ("BM-SP500", "S&P 500 (simulated)"),
    ("BM-MSCI-EAFE", "MSCI EAFE (simulated)"),
    ("BM-RUSSELL2000", "Russell 2000 (simulated)"),
    ("BM-NASDAQ100", "Nasdaq 100 (simulated)"),
]
FUND_IDS = ["F01", "F02", "F03", "F04", "F05"]
FUND_NAMES = {
    "F01": "Diversified Growth Fund",
    "F02": "Core Fixed Income Fund",
    "F03": "Global Equity Fund",
    "F04": "Balanced Allocation Fund",
    "F05": "Short Duration Income Fund",
}


def business_days_ending(end_date, n):
    days = []
    d = end_date
    while len(days) < n:
        if d.weekday() < 5:
            days.append(d)
        d = d - datetime.timedelta(days=1)
    days.reverse()
    return days


def build_security_master(rng):
    rows = []
    idx = 1
    for asset_class, count in ASSET_CLASS_COUNTS:
        for _ in range(count):
            instrument_id = f"INS-{idx:04d}"
            currency = "USD" if asset_class != "Equity" else rng.choice(VALID_CURRENCIES)
            country = rng.choice(["US", "US", "US", "GB", "DE", "JP", "CA"])
            sector = rng.choice(SECTORS) if asset_class == "Equity" else ""
            benchmark_id = rng.choice(BENCHMARKS)[0] if asset_class in ("Equity", "ETF") else ""
            maturity_date = ""
            coupon_rate = ""
            if asset_class == "Bond":
                years_out = rng.randint(2, 15)
                maturity_date = (datetime.date(2026, 8, 28) +
                                  datetime.timedelta(days=365 * years_out)).isoformat()
                coupon_rate = round(rng.uniform(0.01, 0.065), 4)
            rows.append({
                "instrument_id": instrument_id,
                "cusip": f"CUS{idx:06d}",
                "isin": f"US{idx:09d}1",
                "instrument_name": f"{asset_class} Instrument {idx:04d}",
                "asset_class": asset_class,
                "currency": currency,
                "country": country,
                "sector": sector,
                "benchmark_id": benchmark_id,
                "maturity_date": maturity_date,
                "coupon_rate": coupon_rate,
            })
            idx += 1
    return rows


def build_pricing(rng, sec_master, trading_days):
    rows = []
    for inst in sec_master:
        base_price = round(rng.uniform(10, 500), 2)
        price = base_price
        for d in trading_days:
            drift = rng.uniform(-0.01, 0.01)
            price = max(1.0, price * (1 + drift))
            rows.append({
                "instrument_id": inst["instrument_id"],
                "price_date": d.isoformat(),
                "price": round(price, 4),
                "currency": inst["currency"],
            })
    return rows


def build_fx_rates(trading_days):
    rows = []
    rate0 = {"EUR": 1.08, "GBP": 1.27, "JPY": 0.0068, "CAD": 0.74}
    for d in trading_days:
        for ccy, base_rate in rate0.items():
            rows.append({
                "currency_code": ccy,
                "rate_date": d.isoformat(),
                "fx_rate_to_base": round(base_rate * (1 + random.Random(
                    hash((ccy, d)) & 0xffff).uniform(-0.005, 0.005)), 6),
            })
    return rows


def build_positions_and_funds(rng, sec_master, pricing_rows, trading_days):
    price_by_key = {(r["instrument_id"], r["price_date"]): r["price"] for r in pricing_rows}
    positions = []
    funds = []
    for fund_id in FUND_IDS:
        for d in trading_days:
            day_total = 0.0
            day_positions = []
            for inst in sec_master:
                price = price_by_key[(inst["instrument_id"], d.isoformat())]
                qty = round(rng.uniform(100, 5000), 2)
                market_value = round(qty * price, 2)
                day_total += market_value
                day_positions.append({
                    "fund_id": fund_id,
                    "instrument_id": inst["instrument_id"],
                    "position_date": d.isoformat(),
                    "quantity": qty,
                    "market_value": market_value,
                    "local_currency": inst["currency"],
                })
            positions.extend(day_positions)
            # reported NAV = true sum plus small (<0.05%) benign noise, which
            # stays well under the 0.5% tie-out tolerance used by the rule.
            noise = rng.uniform(-0.0005, 0.0005)
            funds.append({
                "fund_id": fund_id,
                "fund_name": FUND_NAMES[fund_id],
                "nav_date": d.isoformat(),
                "reported_nav": round(day_total * (1 + noise), 2),
            })
    return positions, funds


def finalize_lineage(rows, source_file):
    """Assign source_file/source_row (1-based, header is row 1) in place."""
    for i, r in enumerate(rows):
        r["source_file"] = source_file
        r["source_row"] = i + 2


def write_csv(path, rows, fieldnames):
    os.makedirs(os.path.dirname(path), exist_ok=True)
    with open(path, "w", newline="", encoding="utf-8") as f:
        w = csv.DictWriter(f, fieldnames=fieldnames)
        w.writeheader()
        for r in rows:
            w.writerow(r)


def inject_defects(rng, sec_master, pricing, positions, funds):
    """Mutates sec_master/pricing/positions/funds in place, returns the
    defect manifest: one entry per injected scenario, each naming the rule,
    its category, and the anchor row(s) (table/source_row, assigned to
    source_file/source_row AFTER this function returns and finalize_lineage
    runs, so this function records 0-based list index + table name, and the
    caller resolves the final 1-based source_row after lineage is stamped)."""
    counts = defect_counts_for_45()
    manifest = []
    used = {"security_master": set(), "pricing": set(), "positions": set(), "funds": set()}

    def pick(table_rows, table_name, pred=None, n=1):
        picks = []
        attempts = 0
        while len(picks) < n and attempts < 100000:
            attempts += 1
            i = rng.randrange(len(table_rows))
            if i in used[table_name]:
                continue
            if pred and not pred(table_rows[i]):
                continue
            used[table_name].add(i)
            picks.append(i)
        if len(picks) < n:
            raise RuntimeError(f"could not find {n} candidates in {table_name}")
        return picks

    def add_defect(rule, anchors):
        manifest.append({
            "rule_name": rule,
            "rule_category": RULE_CATEGORY[rule],
            "anchors": anchors,  # list of (table_name, row_index)
        })

    # --- pricing rules ---
    # stale_price and missing_price both remove rows from the shared
    # `pricing` list, which would silently invalidate every index recorded
    # by a pick() call made afterward (list removal shifts later indices).
    # Both are therefore resolved immediately, in full, before any other
    # pricing-anchored rule below ever calls pick(pricing, ...).
    stale_order = list(range(len(sec_master)))
    rng.shuffle(stale_order)
    stale_done = 0
    for si in stale_order:
        if stale_done >= counts["stale_price"]:
            break
        inst_id = sec_master[si]["instrument_id"]
        rows_for_inst = [r for r in pricing if r["instrument_id"] == inst_id]
        if len(rows_for_inst) <= 6:
            continue
        rows_for_inst.sort(key=lambda r: r["price_date"])
        cutoff_row = rows_for_inst[-7]
        cutoff_date = cutoff_row["price_date"]
        pricing[:] = [r for r in pricing if not (r["instrument_id"] == inst_id and r["price_date"] > cutoff_date)]
        add_defect("stale_price", [("pricing", pricing.index(cutoff_row))])
        stale_done += 1
    if stale_done < counts["stale_price"]:
        raise RuntimeError("could not find enough instruments with long enough history for stale_price")

    for i in pick(sec_master, "security_master", lambda r: True, counts["missing_price"]):
        inst_id = sec_master[i]["instrument_id"]
        pricing[:] = [r for r in pricing if r["instrument_id"] != inst_id]
        add_defect("missing_price", [("security_master", i)])

    for i in pick(pricing, "pricing", lambda r: True, counts["future_dated_price"]):
        pricing[i]["price_date"] = "2099-01-01"
        add_defect("future_dated_price", [("pricing", i)])

    for i in pick(pricing, "pricing", lambda r: True, counts["negative_price"]):
        pricing[i]["price"] = -round(abs(pricing[i]["price"]), 2)
        add_defect("negative_price", [("pricing", i)])

    for i in pick(pricing, "pricing", lambda r: True, counts["zero_price"]):
        pricing[i]["price"] = 0
        add_defect("zero_price", [("pricing", i)])

    for i in pick(pricing, "pricing", lambda r: True, counts["price_band_breach"]):
        pricing[i]["price"] = round(pricing[i]["price"] * 5.0, 2)
        add_defect("price_band_breach", [("pricing", i)])

    for i in pick(pricing, "pricing", lambda r: True, counts["price_currency_mismatch"]):
        inst_ccy = next(s["currency"] for s in sec_master if s["instrument_id"] == pricing[i]["instrument_id"])
        pricing[i]["currency"] = "JPY" if inst_ccy != "JPY" else "EUR"
        add_defect("price_currency_mismatch", [("pricing", i)])

    # --- security master rules ---
    for i in pick(sec_master, "security_master", lambda r: r["asset_class"] in ("Equity", "ETF"),
                  counts["missing_benchmark"]):
        sec_master[i]["benchmark_id"] = ""
        add_defect("missing_benchmark", [("security_master", i)])

    # each instance is its own two-instrument duplicate-cusip pair, so the
    # manifest entry count for this rule equals counts["duplicate_identifier"]
    # exactly, same as every other rule.
    for _ in range(counts["duplicate_identifier"]):
        idxs = pick(sec_master, "security_master", lambda r: True, 2)
        shared_cusip = f"CUSDUP{idxs[0]:04d}"
        anchors = []
        for i in idxs:
            sec_master[i]["cusip"] = shared_cusip
            anchors.append(("security_master", i))
        add_defect("duplicate_identifier", anchors)

    for i in pick(sec_master, "security_master", lambda r: True, counts["missing_asset_class"]):
        sec_master[i]["asset_class"] = ""
        add_defect("missing_asset_class", [("security_master", i)])

    for i in pick(sec_master, "security_master", lambda r: True, counts["missing_currency_code"]):
        sec_master[i]["currency"] = ""
        add_defect("missing_currency_code", [("security_master", i)])

    for i in pick(sec_master, "security_master", lambda r: True, counts["invalid_currency_code"]):
        sec_master[i]["currency"] = INVALID_CURRENCY_SAMPLE
        add_defect("invalid_currency_code", [("security_master", i)])

    for i in pick(sec_master, "security_master", lambda r: r["asset_class"] == "Equity",
                  counts["missing_sector_for_equity"]):
        sec_master[i]["sector"] = ""
        add_defect("missing_sector_for_equity", [("security_master", i)])

    for i in pick(sec_master, "security_master", lambda r: r["asset_class"] == "Bond",
                  counts["missing_maturity_date_for_bond"]):
        sec_master[i]["maturity_date"] = ""
        add_defect("missing_maturity_date_for_bond", [("security_master", i)])

    for i in pick(sec_master, "security_master", lambda r: r["asset_class"] == "Bond",
                  counts["coupon_rate_out_of_range_for_bond"]):
        sec_master[i]["coupon_rate"] = -0.01
        add_defect("coupon_rate_out_of_range_for_bond", [("security_master", i)])

    # --- position / fund rules ---
    for i in pick(funds, "funds", lambda r: True, counts["holdings_to_nav_tie_out"]):
        funds[i]["reported_nav"] = round(funds[i]["reported_nav"] * 1.08, 2)
        add_defect("holdings_to_nav_tie_out", [("funds", i)])

    for i in pick(positions, "positions", lambda r: True, counts["orphan_position"]):
        positions[i]["instrument_id"] = "INS-9999"
        add_defect("orphan_position", [("positions", i)])

    for i in pick(positions, "positions", lambda r: r["local_currency"] != "" , counts["negative_position_quantity"]):
        positions[i]["quantity"] = -abs(positions[i]["quantity"])
        positions[i]["market_value"] = -abs(positions[i]["market_value"])
        add_defect("negative_position_quantity", [("positions", i)])

    for i in pick(positions, "positions", lambda r: True, counts["duplicate_position_row"]):
        dup = dict(positions[i])
        positions.append(dup)
        add_defect("duplicate_position_row", [("positions", i), ("positions", len(positions) - 1)])

    bond_ids = {s["instrument_id"] for s in sec_master if s["asset_class"] == "Bond"}
    for i in pick(pricing, "pricing", lambda r: r["instrument_id"] in bond_ids,
                  counts["matured_bond_still_priced"]):
        inst = next(s for s in sec_master if s["instrument_id"] == pricing[i]["instrument_id"])
        inst["maturity_date"] = "2020-01-01"
        add_defect("matured_bond_still_priced", [("pricing", i)])

    for i in pick(positions, "positions", lambda r: True, counts["fx_rate_missing_for_nonbase_currency"]):
        positions[i]["local_currency"] = "CHF"  # no fx rate exists for CHF
        add_defect("fx_rate_missing_for_nonbase_currency", [("positions", i)])

    for i in pick(positions, "positions", lambda r: True, counts["position_date_mismatch"]):
        positions[i]["position_date"] = "2000-01-01"  # no pricing row exists for this date
        add_defect("position_date_mismatch", [("positions", i)])

    assert len(manifest) == 45, f"expected 45 seeded defect scenarios, got {len(manifest)}"
    return manifest


def resolve_manifest_lineage(manifest, tables):
    """After finalize_lineage has stamped source_file/source_row, resolve
    each manifest anchor's (table, index) into the actual lineage key,
    tolerating rows removed by stale/suppress post-processing."""
    resolved = []
    for d in manifest:
        anchors = []
        ok = True
        for table_name, idx in d["anchors"]:
            rows = tables[table_name]
            if idx >= len(rows):
                ok = False
                continue
            row = rows[idx]
            anchors.append({
                "source_file": row.get("source_file"),
                "source_row": row.get("source_row"),
            })
        resolved.append({
            "rule_name": d["rule_name"],
            "rule_category": d["rule_category"],
            "anchors": anchors,
            "resolvable": ok and len(anchors) > 0,
        })
    return resolved


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--mode", choices=["clean", "defects"], required=True)
    ap.add_argument("--days", type=int, required=True)
    ap.add_argument("--out-dir", required=True)
    ap.add_argument("--seed", type=int, default=42)
    ap.add_argument("--end-date", default="2026-08-28")
    args = ap.parse_args()

    os.makedirs(args.out_dir, exist_ok=True)
    rng = random.Random(args.seed)
    end_date = datetime.date.fromisoformat(args.end_date)
    trading_days = business_days_ending(end_date, args.days)

    sec_master = build_security_master(rng)
    pricing = build_pricing(rng, sec_master, trading_days)
    positions, funds = build_positions_and_funds(rng, sec_master, pricing, trading_days)
    fx_rates = build_fx_rates(trading_days)

    manifest = []
    if args.mode == "defects":
        manifest = inject_defects(rng, sec_master, pricing, positions, funds)

    finalize_lineage(sec_master, "security_master.csv")
    finalize_lineage(pricing, "pricing.csv")
    finalize_lineage(positions, "positions.csv")
    finalize_lineage(funds, "funds.csv")
    finalize_lineage(fx_rates, "fx_rates.csv")

    if args.mode == "defects":
        resolved = resolve_manifest_lineage(manifest, {
            "security_master": sec_master, "pricing": pricing,
            "positions": positions, "funds": funds,
        })
        unresolved = [d for d in resolved if not d["resolvable"]]
        if unresolved:
            print(f"WARNING: {len(unresolved)} defect(s) lost to post-processing, regenerate with a different seed",
                  file=sys.stderr)
        with open(os.path.join(args.out_dir, "defect_manifest.json"), "w") as f:
            json.dump(resolved, f, indent=2)
        # plain TSV form (rule_name<TAB>anchor_keys) the Java scorer reads,
        # to avoid needing a JSON parser in the Java service.
        with open(os.path.join(args.out_dir, "defect_manifest.tsv"), "w") as f:
            for d in resolved:
                keys = ";".join(f"{a['source_file']}|{a['source_row']}" for a in d["anchors"])
                f.write(f"{d['rule_name']}\t{keys}\n")

    write_csv(os.path.join(args.out_dir, "security_master.csv"), sec_master,
              ["instrument_id", "cusip", "isin", "instrument_name", "asset_class", "currency",
               "country", "sector", "benchmark_id", "maturity_date", "coupon_rate",
               "source_file", "source_row"])
    write_csv(os.path.join(args.out_dir, "pricing.csv"), pricing,
              ["instrument_id", "price_date", "price", "currency", "source_file", "source_row"])
    write_csv(os.path.join(args.out_dir, "positions.csv"), positions,
              ["fund_id", "instrument_id", "position_date", "quantity", "market_value",
               "local_currency", "source_file", "source_row"])
    write_csv(os.path.join(args.out_dir, "funds.csv"), funds,
              ["fund_id", "fund_name", "nav_date", "reported_nav", "source_file", "source_row"])
    write_csv(os.path.join(args.out_dir, "fx_rates.csv"), fx_rates,
              ["currency_code", "rate_date", "fx_rate_to_base", "source_file", "source_row"])
    write_csv(os.path.join(args.out_dir, "ref_benchmarks.csv"),
              [{"benchmark_id": b, "benchmark_name": n} for b, n in BENCHMARKS],
              ["benchmark_id", "benchmark_name"])
    write_csv(os.path.join(args.out_dir, "ref_currency.csv"),
              [{"currency_code": c, "currency_name": c} for c in VALID_CURRENCIES],
              ["currency_code", "currency_name"])

    print(f"wrote {len(sec_master)} security_master, {len(pricing)} pricing, "
          f"{len(positions)} positions, {len(funds)} funds rows to {args.out_dir}")
    if args.mode == "defects":
        print(f"injected {len(manifest)} seeded defect scenarios")


if __name__ == "__main__":
    main()
