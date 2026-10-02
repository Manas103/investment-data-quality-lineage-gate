package com.mfs.idqg.rules;

import com.mfs.idqg.CsvUtil;
import com.mfs.idqg.domain.Violation;

import java.io.IOException;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Loads one generated data corpus (security_master, pricing, positions,
 * funds, fx_rates, ref_benchmarks, ref_currency CSVs) and runs all 22 named
 * rules, ported one-for-one from the dbt SQL tests under dbt/tests/ (those
 * remain the structurally-validated reference definition of each rule; this
 * engine is the independent Java re-implementation that is actually run at
 * scale and from which the quarantine / audit log / owner routing claims
 * are measured).
 */
public final class RuleEngine {
    public static final Map<String, String> RULE_CATEGORY = new LinkedHashMap<>();

    static {
        for (String r : new String[]{"stale_price", "missing_price", "future_dated_price",
                "negative_price", "zero_price", "price_band_breach", "price_currency_mismatch"}) {
            RULE_CATEGORY.put(r, "pricing");
        }
        for (String r : new String[]{"missing_benchmark", "duplicate_identifier", "missing_asset_class",
                "missing_currency_code", "invalid_currency_code", "missing_sector_for_equity",
                "missing_maturity_date_for_bond", "coupon_rate_out_of_range_for_bond"}) {
            RULE_CATEGORY.put(r, "security_master");
        }
        for (String r : new String[]{"holdings_to_nav_tie_out", "orphan_position",
                "negative_position_quantity", "duplicate_position_row", "matured_bond_still_priced",
                "fx_rate_missing_for_nonbase_currency", "position_date_mismatch"}) {
            RULE_CATEGORY.put(r, "position_fund");
        }
        if (RULE_CATEGORY.size() != 22) {
            throw new IllegalStateException("expected 22 named rules, found " + RULE_CATEGORY.size());
        }
    }

    public List<Map<String, String>> securityMaster;
    public List<Map<String, String>> pricing;
    public List<Map<String, String>> positions;
    public List<Map<String, String>> funds;
    public List<Map<String, String>> fxRates;
    public List<Map<String, String>> refBenchmarks;
    public List<Map<String, String>> refCurrency;

    public void load(Path dir) throws IOException {
        securityMaster = CsvUtil.read(dir.resolve("security_master.csv"));
        pricing = CsvUtil.read(dir.resolve("pricing.csv"));
        positions = CsvUtil.read(dir.resolve("positions.csv"));
        funds = CsvUtil.read(dir.resolve("funds.csv"));
        fxRates = CsvUtil.read(dir.resolve("fx_rates.csv"));
        refBenchmarks = CsvUtil.read(dir.resolve("ref_benchmarks.csv"));
        refCurrency = CsvUtil.read(dir.resolve("ref_currency.csv"));
    }

    public int instrumentCount() {
        return securityMaster.size();
    }

    public List<Violation> runAll() {
        List<Violation> out = new ArrayList<>();

        Map<String, Map<String, String>> smByInstrument = new HashMap<>();
        for (Map<String, String> sm : securityMaster) {
            smByInstrument.put(sm.get("instrument_id"), sm);
        }
        Set<String> validBenchmarks = new HashSet<>();
        for (Map<String, String> b : refBenchmarks) {
            validBenchmarks.add(b.get("benchmark_id"));
        }
        Set<String> validCurrencies = new HashSet<>();
        for (Map<String, String> c : refCurrency) {
            validCurrencies.add(c.get("currency_code"));
        }
        Set<String> fxKeys = new HashSet<>();
        for (Map<String, String> fx : fxRates) {
            fxKeys.add(fx.get("currency_code") + "|" + fx.get("rate_date"));
        }
        Set<String> priceInstrumentDates = new HashSet<>();
        Map<String, LocalDate> latestPriceDateByInstrument = new HashMap<>();
        Map<String, List<Map<String, String>>> pricingByInstrument = new HashMap<>();
        LocalDate datasetMaxDate = null;
        for (Map<String, String> p : pricing) {
            String inst = p.get("instrument_id");
            LocalDate d = LocalDate.parse(p.get("price_date"));
            priceInstrumentDates.add(inst + "|" + p.get("price_date"));
            pricingByInstrument.computeIfAbsent(inst, k -> new ArrayList<>()).add(p);
            LocalDate cur = latestPriceDateByInstrument.get(inst);
            if (cur == null || d.isAfter(cur)) {
                latestPriceDateByInstrument.put(inst, d);
            }
            if (datasetMaxDate == null || d.isAfter(datasetMaxDate)) {
                datasetMaxDate = d;
            }
        }
        Map<String, Integer> cusipCount = new HashMap<>();
        for (Map<String, String> sm : securityMaster) {
            String cusip = sm.get("cusip");
            if (!CsvUtil.isBlank(cusip)) {
                cusipCount.merge(cusip, 1, Integer::sum);
            }
        }
        Map<String, Double> trueTotalByFundDate = new HashMap<>();
        for (Map<String, String> pos : positions) {
            String key = pos.get("fund_id") + "|" + pos.get("position_date");
            trueTotalByFundDate.merge(key, CsvUtil.parseDouble(pos.get("market_value"), 0), Double::sum);
        }
        Map<String, Integer> positionKeyCount = new HashMap<>();
        for (Map<String, String> pos : positions) {
            String key = pos.get("fund_id") + "|" + pos.get("instrument_id") + "|" + pos.get("position_date");
            positionKeyCount.merge(key, 1, Integer::sum);
        }
        String today = LocalDate.now().toString();

        // --- pricing rules ---
        for (Map<String, String> p : pricing) {
            Map<String, String> snap = new LinkedHashMap<>(p);
            snap.put("_dataset_max_date", datasetMaxDate.toString());
            LocalDate latest = latestPriceDateByInstrument.get(p.get("instrument_id"));
            if (latest != null && latest.toString().equals(p.get("price_date"))
                    && RulePredicates.stalePrice(snap)) {
                add(out, "stale_price", p, "price_date",
                        "latest price for " + p.get("instrument_id") + " is stale vs dataset max " + datasetMaxDate,
                        snap);
            }
            snap.put("_as_of_today", today);
            if (RulePredicates.futureDatedPrice(snap)) {
                add(out, "future_dated_price", p, "price_date", "price_date is after the run date", snap);
            }
            if (RulePredicates.negativePrice(snap)) {
                add(out, "negative_price", p, "price", "price is negative", snap);
            }
            if (RulePredicates.zeroPrice(snap)) {
                add(out, "zero_price", p, "price", "price is exactly zero", snap);
            }
            Map<String, String> sm = smByInstrument.get(p.get("instrument_id"));
            if (sm != null) {
                snap.put("_security_master_currency", sm.get("currency"));
                if (RulePredicates.priceCurrencyMismatch(snap)) {
                    add(out, "price_currency_mismatch", p, "currency",
                            "pricing currency disagrees with security master currency", snap);
                }
                if (sm.get("asset_class").equals("Bond") && !CsvUtil.isBlank(sm.get("maturity_date"))) {
                    snap.put("_maturity_date", sm.get("maturity_date"));
                    if (RulePredicates.maturedBondStillPriced(snap)) {
                        add(out, "matured_bond_still_priced", p, "price_date",
                                "bond priced after its own maturity_date", snap);
                    }
                }
            }
        }
        for (List<Map<String, String>> series : pricingByInstrument.values()) {
            series.sort((a, b) -> a.get("price_date").compareTo(b.get("price_date")));
            double prev = -1;
            for (Map<String, String> p : series) {
                double price = CsvUtil.parseDouble(p.get("price"), 0);
                if (prev > 0 && price > 0) {
                    Map<String, String> snap = new LinkedHashMap<>(p);
                    snap.put("_prev_price", Double.toString(prev));
                    if (RulePredicates.priceBandBreach(snap)) {
                        add(out, "price_band_breach", p, "price",
                                "price moved more than 50% from the prior session", snap);
                    }
                }
                if (price > 0) {
                    prev = price;
                }
            }
        }

        // --- security master rules ---
        for (Map<String, String> sm : securityMaster) {
            Map<String, String> snap = new LinkedHashMap<>(sm);
            boolean hasAnyPrice = pricingByInstrument.containsKey(sm.get("instrument_id"));
            snap.put("_has_any_price", Boolean.toString(hasAnyPrice));
            if (!RulePredicates.missingPrice(snap)) {
                // only add when it actually fires; evaluate explicitly
            }
            if (!hasAnyPrice) {
                add(out, "missing_price", sm, "instrument_id", "instrument has no pricing rows", snap);
            }
            snap.put("_benchmark_resolves", Boolean.toString(validBenchmarks.contains(sm.get("benchmark_id"))));
            if (RulePredicates.missingBenchmark(snap)) {
                add(out, "missing_benchmark", sm, "benchmark_id", "no resolvable benchmark_id", snap);
            }
            int cc = cusipCount.getOrDefault(sm.get("cusip"), 0);
            snap.put("_cusip_count", Integer.toString(cc));
            if (RulePredicates.duplicateIdentifier(snap)) {
                add(out, "duplicate_identifier", sm, "cusip", "cusip shared by more than one instrument", snap);
            }
            if (RulePredicates.missingAssetClass(snap)) {
                add(out, "missing_asset_class", sm, "asset_class", "no asset_class", snap);
            }
            if (RulePredicates.missingCurrencyCode(snap)) {
                add(out, "missing_currency_code", sm, "currency", "no currency code", snap);
            }
            snap.put("_currency_is_valid_iso", Boolean.toString(validCurrencies.contains(sm.get("currency"))));
            if (RulePredicates.invalidCurrencyCode(snap)) {
                add(out, "invalid_currency_code", sm, "currency", "currency code not a recognized ISO code", snap);
            }
            if (RulePredicates.missingSectorForEquity(snap)) {
                add(out, "missing_sector_for_equity", sm, "sector", "equity instrument has no sector", snap);
            }
            if (RulePredicates.missingMaturityDateForBond(snap)) {
                add(out, "missing_maturity_date_for_bond", sm, "maturity_date", "bond has no maturity_date", snap);
            }
            if (RulePredicates.couponRateOutOfRangeForBond(snap)) {
                add(out, "coupon_rate_out_of_range_for_bond", sm, "coupon_rate", "coupon_rate out of 0-20% range", snap);
            }
        }

        // --- position / fund rules ---
        for (Map<String, String> pos : positions) {
            Map<String, String> snap = new LinkedHashMap<>(pos);
            boolean exists = smByInstrument.containsKey(pos.get("instrument_id"));
            snap.put("_instrument_exists", Boolean.toString(exists));
            if (RulePredicates.orphanPosition(snap)) {
                add(out, "orphan_position", pos, "instrument_id", "instrument_id not present in security master", snap);
            }
            Map<String, String> sm = smByInstrument.get(pos.get("instrument_id"));
            snap.put("_security_master_asset_class", sm != null ? sm.get("asset_class") : "");
            if (RulePredicates.negativePositionQuantity(snap)) {
                add(out, "negative_position_quantity", pos, "quantity", "negative quantity for a non-Cash position", snap);
            }
            int keyCount = positionKeyCount.getOrDefault(
                    pos.get("fund_id") + "|" + pos.get("instrument_id") + "|" + pos.get("position_date"), 1);
            snap.put("_key_count", Integer.toString(keyCount));
            if (RulePredicates.duplicatePositionRow(snap)) {
                add(out, "duplicate_position_row", pos, "position_date", "(fund, instrument, date) appears more than once", snap);
            }
            boolean fxExists = fxKeys.contains(pos.get("local_currency") + "|" + pos.get("position_date"));
            snap.put("_fx_rate_exists", Boolean.toString(fxExists));
            if (RulePredicates.fxRateMissingForNonbaseCurrency(snap)) {
                add(out, "fx_rate_missing_for_nonbase_currency", pos, "local_currency", "no fx rate for this currency/date", snap);
            }
            boolean priceExists = priceInstrumentDates.contains(pos.get("instrument_id") + "|" + pos.get("position_date"));
            snap.put("_price_exists_for_date", Boolean.toString(priceExists));
            if (RulePredicates.positionDateMismatch(snap)) {
                add(out, "position_date_mismatch", pos, "position_date", "no pricing row for this instrument/date", snap);
            }
        }
        for (Map<String, String> f : funds) {
            Map<String, String> snap = new LinkedHashMap<>(f);
            double trueTotal = trueTotalByFundDate.getOrDefault(f.get("fund_id") + "|" + f.get("nav_date"), 0.0);
            snap.put("_true_total", Double.toString(trueTotal));
            if (RulePredicates.holdingsToNavTieOut(snap)) {
                add(out, "holdings_to_nav_tie_out", f, "reported_nav",
                        "reported_nav disagrees with true holdings total by more than 0.5%", snap);
            }
        }

        return out;
    }

    private void add(List<Violation> out, String rule, Map<String, String> row, String column,
                      String detail, Map<String, String> snapshot) {
        out.add(new Violation(rule, RULE_CATEGORY.get(rule), row.get("source_file"), row.get("source_row"),
                column, detail, snapshot));
    }
}
