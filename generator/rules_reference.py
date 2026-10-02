"""
Single source of truth for the 22 named data-quality rules, their category
(pricing / security_master / position_fund) and the business owner each
category routes to. The generator imports this to tag injected defects; the
dbt tests and the Java RuleOracle each reimplement the same 22 predicates
independently (on purpose, see README "Why three independent
implementations"), so this module is deliberately NOT imported by dbt or
Java. It exists only to keep the generator's defect-injection labels
consistent with the names used everywhere else.
"""

OWNER_BY_CATEGORY = {
    "pricing": "Pricing Operations - D. Alvarez",
    "security_master": "Security Master - R. Chen",
    "position_fund": "Fund Accounting - T. Osei",
}

# Ordered so the extra (45th) seeded defect lands on duplicate_identifier,
# the rule explicitly named in the resume claim.
RULES = [
    ("stale_price", "pricing"),
    ("missing_price", "pricing"),
    ("future_dated_price", "pricing"),
    ("negative_price", "pricing"),
    ("zero_price", "pricing"),
    ("price_band_breach", "pricing"),
    ("price_currency_mismatch", "pricing"),
    ("missing_benchmark", "security_master"),
    ("duplicate_identifier", "security_master"),
    ("missing_asset_class", "security_master"),
    ("missing_currency_code", "security_master"),
    ("invalid_currency_code", "security_master"),
    ("missing_sector_for_equity", "security_master"),
    ("missing_maturity_date_for_bond", "security_master"),
    ("coupon_rate_out_of_range_for_bond", "security_master"),
    ("holdings_to_nav_tie_out", "position_fund"),
    ("orphan_position", "position_fund"),
    ("negative_position_quantity", "position_fund"),
    ("duplicate_position_row", "position_fund"),
    ("matured_bond_still_priced", "position_fund"),
    ("fx_rate_missing_for_nonbase_currency", "position_fund"),
    ("position_date_mismatch", "position_fund"),
]

assert len(RULES) == 22, f"expected 22 named rules, got {len(RULES)}"
RULE_NAMES = [r[0] for r in RULES]
RULE_CATEGORY = dict(RULES)


def defect_counts_for_45():
    """Distribute 45 seeded defects across the 22 rules: 2 each (44),
    plus one extra on duplicate_identifier (the rule named in the resume
    claim text), for 45 total."""
    counts = {name: 2 for name in RULE_NAMES}
    counts["duplicate_identifier"] += 1
    total = sum(counts.values())
    assert total == 45, f"expected 45 seeded defects, got {total}"
    return counts
