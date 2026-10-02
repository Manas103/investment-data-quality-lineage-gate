package com.mfs.idqg;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Minimal CSV reader for the generator's own output. The generator never
 * emits a comma, quote or newline inside a field, so a plain split on comma
 * is correct here and avoids a third-party CSV dependency.
 */
public final class CsvUtil {
    private CsvUtil() {
    }

    public static List<Map<String, String>> read(Path path) throws IOException {
        List<String> lines = Files.readAllLines(path);
        List<Map<String, String>> rows = new ArrayList<>();
        if (lines.isEmpty()) {
            return rows;
        }
        String[] header = lines.get(0).split(",", -1);
        for (int i = 1; i < lines.size(); i++) {
            String line = lines.get(i);
            if (line.isEmpty()) {
                continue;
            }
            String[] values = line.split(",", -1);
            Map<String, String> row = new LinkedHashMap<>();
            for (int c = 0; c < header.length; c++) {
                row.put(header[c], c < values.length ? values[c] : "");
            }
            rows.add(row);
        }
        return rows;
    }

    public static boolean isBlank(String s) {
        return s == null || s.isEmpty();
    }

    public static double parseDouble(String s, double fallback) {
        if (isBlank(s)) {
            return fallback;
        }
        try {
            return Double.parseDouble(s);
        } catch (NumberFormatException e) {
            return fallback;
        }
    }
}
