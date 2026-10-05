package com.mfs.idqg.ingestion;

import java.util.ArrayList;
import java.util.List;

/**
 * Builds the 40 declared vendor schemas deterministically. Every schema declares the same 10
 * column names (this keeps every row self-describing for vendor/schema/instrument grouping
 * regardless of which columns a given defect test happens to touch); what makes each of the 40
 * a distinct contract is its asset class, its value1 range, and whether it allows a negative
 * value1, each of which a dedicated health check is sensitive to. Schema drift (the thing the
 * drift health checks target) is a mismatch between this declaration and what a specific file's
 * own header actually contains, which is injected per-file, not per-schema.
 */
public final class SchemaRegistry {

    public static final List<String> FIELD_POOL = List.of(
            "instrument_id", "as_of_date", "received_at", "currency", "value1", "value2",
            "status", "region", "vendor", "schema_name");

    public static final List<String> ALWAYS_REQUIRED = List.of("instrument_id", "as_of_date", "vendor", "schema_name");

    private static final String[] ASSET_CLASSES = {"Equity", "FixedIncome", "FX", "Commodity", "Derivative"};

    private final List<VendorSchema> schemas;

    public SchemaRegistry() {
        List<VendorSchema> built = new ArrayList<>();
        for (int i = 0; i < 40; i++) {
            String assetClass = ASSET_CLASSES[i % ASSET_CLASSES.length];
            String name = assetClass.toLowerCase() + "_vendor_feed_" + (i + 1);
            List<String> declared = FIELD_POOL;

            double upperBound = switch (assetClass) {
                case "Equity" -> 10_000.0;
                case "FixedIncome" -> 200.0;
                case "FX" -> 10.0;
                case "Commodity" -> 5_000.0;
                default -> 1_000_000.0;
            };
            built.add(new VendorSchema(name, assetClass, List.copyOf(declared),
                    ALWAYS_REQUIRED, upperBound, assetClass.equals("Derivative")));
        }
        this.schemas = List.copyOf(built);
    }

    public List<VendorSchema> all() {
        return schemas;
    }
}
