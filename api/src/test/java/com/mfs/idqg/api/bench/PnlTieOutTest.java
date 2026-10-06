package com.mfs.idqg.api.bench;

import com.mfs.idqg.api.store.CorpusLoader;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The scale claim: P&amp;L tied out to holdings over 1.2M records. Two independent aggregations
 * of the same 480-instrument, 5-fund, 500-trading-day position corpus (5 x 500 x 480 =
 * 1,200,000 position rows, the same corpus shape the original engine's false-quarantine claim
 * uses): a bottom-up per-position day-over-day P&amp;L sum, and a top-down fund-level
 * holdings-total delta computed without ever pairing individual positions across days. Every
 * one of the 2,495 fund-date P&amp;L figures (5 funds x 499 day-over-day transitions) from the
 * two paths must agree to the cent; a real mismatch would mean a position went missing or
 * duplicated between two consecutive days. Raw output: docs/pnl_tieout_output.txt.
 */
class PnlTieOutTest {

    @Test
    void pnlTiesOutToHoldingsOver1Point2MillionRecords(@TempDir Path ignored) throws IOException, InterruptedException {
        Path corpusDir = Files.createTempDirectory("idqg-pnl-corpus");
        long genStart = System.nanoTime();
        CacheLatencyBenchmark.generateCorpus(generatorPath(), corpusDir, 500);
        double genSeconds = (System.nanoTime() - genStart) / 1_000_000_000.0;

        long loadStart = System.nanoTime();
        List<Map<String, String>> positions = CorpusLoader.read(corpusDir.resolve("positions.csv"));
        double loadSeconds = (System.nanoTime() - loadStart) / 1_000_000_000.0;
        assertEquals(1_200_000, positions.size());

        long computeStart = System.nanoTime();

        // Bottom-up: per (fund, instrument), sort by date, sum day-over-day market_value deltas.
        Map<String, List<Map<String, String>>> byFundInstrument = new HashMap<>();
        for (Map<String, String> p : positions) {
            String key = p.get("fund_id") + "|" + p.get("instrument_id");
            byFundInstrument.computeIfAbsent(key, k -> new java.util.ArrayList<>()).add(p);
        }
        Map<String, Double> bottomUpPnlByFundDate = new TreeMap<>();
        for (List<Map<String, String>> series : byFundInstrument.values()) {
            series.sort((a, b) -> a.get("position_date").compareTo(b.get("position_date")));
            for (int i = 1; i < series.size(); i++) {
                Map<String, String> prev = series.get(i - 1);
                Map<String, String> cur = series.get(i);
                double delta = Double.parseDouble(cur.get("market_value")) - Double.parseDouble(prev.get("market_value"));
                String fundDateKey = cur.get("fund_id") + "|" + cur.get("position_date");
                bottomUpPnlByFundDate.merge(fundDateKey, delta, Double::sum);
            }
        }

        // Top-down: independently sum market_value per (fund, date), then diff consecutive totals.
        Map<String, Double> holdingsTotalByFundDate = new TreeMap<>();
        for (Map<String, String> p : positions) {
            String key = p.get("fund_id") + "|" + p.get("position_date");
            holdingsTotalByFundDate.merge(key, Double.parseDouble(p.get("market_value")), Double::sum);
        }
        Map<String, List<String>> datesByFund = new HashMap<>();
        for (String key : holdingsTotalByFundDate.keySet()) {
            String[] parts = key.split("\\|", 2);
            datesByFund.computeIfAbsent(parts[0], k -> new java.util.ArrayList<>()).add(parts[1]);
        }
        Map<String, Double> topDownPnlByFundDate = new TreeMap<>();
        for (Map.Entry<String, List<String>> e : datesByFund.entrySet()) {
            String fund = e.getKey();
            List<String> dates = e.getValue();
            dates.sort(String::compareTo);
            for (int i = 1; i < dates.size(); i++) {
                double prevTotal = holdingsTotalByFundDate.get(fund + "|" + dates.get(i - 1));
                double curTotal = holdingsTotalByFundDate.get(fund + "|" + dates.get(i));
                topDownPnlByFundDate.put(fund + "|" + dates.get(i), curTotal - prevTotal);
            }
        }
        double computeSeconds = (System.nanoTime() - computeStart) / 1_000_000_000.0;

        assertEquals(topDownPnlByFundDate.size(), bottomUpPnlByFundDate.size());
        int mismatches = 0;
        double maxAbsDiff = 0.0;
        for (String key : topDownPnlByFundDate.keySet()) {
            double diff = Math.abs(topDownPnlByFundDate.get(key) - bottomUpPnlByFundDate.get(key));
            maxAbsDiff = Math.max(maxAbsDiff, diff);
            if (diff > 0.01) {
                mismatches++;
            }
        }

        String report = String.format(
                "P&L tie-out to holdings benchmark%n"
                + "Machine: Windows 11 Home, JDK 21 Temurin, Maven 3.9.9%n"
                + "Corpus: 480 instruments x 5 funds x 500 trading days = 1,200,000 position rows%n"
                + "Corpus generation: %.2fs, CSV load: %.2fs, tie-out computation: %.2fs%n"
                + "Fund-date P&L figures compared: %d (5 funds x 499 day-over-day transitions)%n"
                + "Mismatches (> $0.01 absolute difference between the two independent aggregations): %d%n"
                + "Max absolute difference observed: $%.6f%n"
                + "Claim: P&L tied out to holdings over 1.2M records%n",
                genSeconds, loadSeconds, computeSeconds, topDownPnlByFundDate.size(), mismatches, maxAbsDiff);

        Path docsDir = Path.of("docs");
        Files.createDirectories(docsDir);
        Files.writeString(docsDir.resolve("pnl_tieout_output.txt"), report, StandardCharsets.UTF_8);
        System.out.println(report);

        assertEquals(2495, topDownPnlByFundDate.size());
        assertTrue(mismatches == 0, "every fund-date P&L figure must tie out between the two independent paths");
    }

    private static Path generatorPath() {
        return Path.of("..", "generator", "generate.py").toAbsolutePath().normalize();
    }
}
