package com.mfs.idqg.ingestion;

import java.util.List;

/**
 * One of the 40 declared vendor feed schemas. Every schema shares the same underlying field
 * pool (this is a simulated vendor landscape, not 40 unrelated systems) but declares its own
 * column subset and order, which is exactly what schema drift is checked against: a file that
 * arrives with a different column set than its schema declares.
 */
public record VendorSchema(
        String name,
        String assetClass,
        List<String> declaredColumns,
        List<String> requiredColumns,
        double value1UpperBound,
        boolean allowNegativeValue1) {
}
