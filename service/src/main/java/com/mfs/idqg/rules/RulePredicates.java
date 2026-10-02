package com.mfs.idqg.rules;

import com.mfs.idqg.CsvUtil;

import java.time.LocalDate;
import java.util.Map;

/**
 * The 22 named rule predicates, each operating ONLY on a snapshot map (the
 * row's own fields plus whatever comparison context the rule needs, e.g.
 * "_dataset_max_date" or "_true_total"). RuleEngine calls these at detection
 * time to decide what to quarantine; AuditReplay calls the exact same
 * methods again later, fed only the snapshot stored in the audit log, to
 * prove a quarantine decision is reproducible from the audit log alone
 * without re-reading the original CSVs.
 */
public final class RulePredicates {
    private RulePredicates() {
    }

    private static String v(Map<String, String> s, String k) {
        return s.getOrDefault(k, "");
    }

    public static boolean stalePrice(Map<String, String> s) {
        LocalDate latest = LocalDate.parse(v(s, "price_date"));
        LocalDate datasetMax = LocalDate.parse(v(s, "_dataset_max_date"));
        return latest.isBefore(datasetMax.minusDays(5));
    }

    public static boolean missingPrice(Map<String, String> s) {
        return "true".equals(v(s, "_has_any_price")) ? false : true;
    }

    public static boolean futureDatedPrice(Map<String, String> s) {
        LocalDate d = LocalDate.parse(v(s, "price_date"));
        LocalDate today = LocalDate.parse(v(s, "_as_of_today"));
        return d.isAfter(today);
    }

    public static boolean negativePrice(Map<String, String> s) {
        return CsvUtil.parseDouble(v(s, "price"), 0) < 0;
    }

    public static boolean zeroPrice(Map<String, String> s) {
        return CsvUtil.parseDouble(v(s, "price"), -1) == 0;
    }

    public static boolean priceBandBreach(Map<String, String> s) {
        double price = CsvUtil.parseDouble(v(s, "price"), 0);
        double prev = CsvUtil.parseDouble(v(s, "_prev_price"), 0);
        if (prev == 0) {
            return false;
        }
        return Math.abs(price / prev - 1) > 0.5;
    }

    public static boolean priceCurrencyMismatch(Map<String, String> s) {
        String pc = v(s, "currency");
        String sc = v(s, "_security_master_currency");
        return !pc.equals(sc);
    }

    public static boolean missingBenchmark(Map<String, String> s) {
        String ac = v(s, "asset_class");
        if (!ac.equals("Equity") && !ac.equals("ETF")) {
            return false;
        }
        String bid = v(s, "benchmark_id");
        return CsvUtil.isBlank(bid) || !"true".equals(v(s, "_benchmark_resolves"));
    }

    public static boolean duplicateIdentifier(Map<String, String> s) {
        String cusip = v(s, "cusip");
        if (CsvUtil.isBlank(cusip)) {
            return false;
        }
        int count = (int) CsvUtil.parseDouble(v(s, "_cusip_count"), 1);
        return count > 1;
    }

    public static boolean missingAssetClass(Map<String, String> s) {
        return CsvUtil.isBlank(v(s, "asset_class"));
    }

    public static boolean missingCurrencyCode(Map<String, String> s) {
        return CsvUtil.isBlank(v(s, "currency"));
    }

    public static boolean invalidCurrencyCode(Map<String, String> s) {
        String c = v(s, "currency");
        return !CsvUtil.isBlank(c) && !"true".equals(v(s, "_currency_is_valid_iso"));
    }

    public static boolean missingSectorForEquity(Map<String, String> s) {
        return v(s, "asset_class").equals("Equity") && CsvUtil.isBlank(v(s, "sector"));
    }

    public static boolean missingMaturityDateForBond(Map<String, String> s) {
        return v(s, "asset_class").equals("Bond") && CsvUtil.isBlank(v(s, "maturity_date"));
    }

    public static boolean couponRateOutOfRangeForBond(Map<String, String> s) {
        if (!v(s, "asset_class").equals("Bond")) {
            return false;
        }
        String cr = v(s, "coupon_rate");
        if (CsvUtil.isBlank(cr)) {
            return false;
        }
        double rate = Double.parseDouble(cr);
        return rate < 0 || rate > 0.20;
    }

    public static boolean holdingsToNavTieOut(Map<String, String> s) {
        double trueTotal = CsvUtil.parseDouble(v(s, "_true_total"), 0);
        String navStr = v(s, "reported_nav");
        if (CsvUtil.isBlank(navStr)) {
            return true;
        }
        double nav = Double.parseDouble(navStr);
        if (nav == 0) {
            return true;
        }
        return Math.abs(trueTotal - nav) / Math.abs(nav) > 0.005;
    }

    public static boolean orphanPosition(Map<String, String> s) {
        return !"true".equals(v(s, "_instrument_exists"));
    }

    public static boolean negativePositionQuantity(Map<String, String> s) {
        double qty = CsvUtil.parseDouble(v(s, "quantity"), 0);
        String assetClass = v(s, "_security_master_asset_class");
        return qty < 0 && !assetClass.equals("Cash");
    }

    public static boolean duplicatePositionRow(Map<String, String> s) {
        int count = (int) CsvUtil.parseDouble(v(s, "_key_count"), 1);
        return count > 1;
    }

    public static boolean maturedBondStillPriced(Map<String, String> s) {
        String maturity = v(s, "_maturity_date");
        if (CsvUtil.isBlank(maturity)) {
            return false;
        }
        LocalDate priceDate = LocalDate.parse(v(s, "price_date"));
        return priceDate.isAfter(LocalDate.parse(maturity));
    }

    public static boolean fxRateMissingForNonbaseCurrency(Map<String, String> s) {
        String ccy = v(s, "local_currency");
        if (CsvUtil.isBlank(ccy) || ccy.equals("USD")) {
            return false;
        }
        return !"true".equals(v(s, "_fx_rate_exists"));
    }

    public static boolean positionDateMismatch(Map<String, String> s) {
        return !"true".equals(v(s, "_price_exists_for_date"));
    }
}
