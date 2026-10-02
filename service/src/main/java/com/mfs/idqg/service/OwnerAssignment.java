package com.mfs.idqg.service;

import java.util.Map;

/** Static owner-assignment table: rule category -> named business owner. */
public final class OwnerAssignment {
    public static final Map<String, String> OWNER_BY_CATEGORY = Map.of(
            "pricing", "Pricing Operations - D. Alvarez",
            "security_master", "Security Master - R. Chen",
            "position_fund", "Fund Accounting - T. Osei"
    );

    private OwnerAssignment() {
    }

    public static String ownerFor(String ruleCategory) {
        String owner = OWNER_BY_CATEGORY.get(ruleCategory);
        if (owner == null) {
            throw new IllegalArgumentException("no owner assignment for category " + ruleCategory);
        }
        return owner;
    }
}
