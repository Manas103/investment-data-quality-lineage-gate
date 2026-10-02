package com.mfs.idqg;

import com.mfs.idqg.domain.Violation;
import com.mfs.idqg.rules.RuleEngine;
import com.mfs.idqg.service.AuditLog;
import com.mfs.idqg.service.OwnerAssignment;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RuleEngineTest {
    static List<Violation> violations;
    static RuleEngine engine;

    @BeforeAll
    static void run() throws Exception {
        Path fixture = Paths.get(
                RuleEngineTest.class.getResource("/fixture/security_master.csv").toURI()).getParent();
        engine = new RuleEngine();
        engine.load(fixture);
        violations = engine.runAll();
    }

    @Test
    void finds22NamedRuleImplementations() {
        assertEquals(22, RuleEngine.RULE_CATEGORY.size());
    }

    @Test
    void catchesEachPlantedDefectByItsNamedRule() {
        Map<String, Long> byRule = new HashMap<>();
        for (Violation v : violations) {
            byRule.merge(v.ruleName, 1L, Long::sum);
        }
        assertEquals(1L, byRule.get("missing_benchmark"));
        assertEquals(2L, byRule.get("duplicate_identifier"));
        assertEquals(1L, byRule.get("negative_price"));
        assertEquals(1L, byRule.get("missing_price"));
        assertEquals(1L, byRule.get("orphan_position"));
        assertEquals(1L, byRule.get("negative_position_quantity"));
        assertEquals(1L, byRule.get("position_date_mismatch"));
        // holdings_to_nav_tie_out must NOT fire: true total (10000+500-500) ties
        // exactly to the fixture's reported_nav of 10000.
        assertFalse(byRule.containsKey("holdings_to_nav_tie_out"));
    }

    @Test
    void everyViolationHasNonBlankLineageAndResolvesToANamedOwner() {
        assertFalse(violations.isEmpty());
        for (Violation v : violations) {
            assertFalse(v.sourceFile == null || v.sourceFile.isEmpty(), "missing source_file for " + v.ruleName);
            assertFalse(v.sourceRow == null || v.sourceRow.isEmpty(), "missing source_row for " + v.ruleName);
            String owner = OwnerAssignment.ownerFor(v.ruleCategory);
            assertTrue(owner != null && !owner.isEmpty());
        }
    }

    @Test
    void everyQuarantineIsReproducibleFromTheAuditLogAlone(@TempDir Path tmp) throws Exception {
        Path csv = tmp.resolve("quarantine_records.csv");
        Path auditLog = tmp.resolve("audit_log.log");
        AuditLog.writeAll(violations, "test-run", csv, auditLog);

        int[] result = AuditLog.replayAll(auditLog);
        int total = result[0];
        int reproduced = result[1];
        assertEquals(violations.size(), total);
        assertEquals(total, reproduced, "every replayed quarantine must reproduce its original decision");
    }
}
