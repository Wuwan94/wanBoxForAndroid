package io.nekohasekai.sagernet

import io.nekohasekai.sagernet.database.RuleEntity
import io.nekohasekai.sagernet.route.OutboundReferenceValidator
import io.nekohasekai.sagernet.route.OutboundReferenceValidator.Issue
import io.nekohasekai.sagernet.route.OutboundReferenceValidator.ReferenceKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class OutboundReferenceValidatorTest {
    @Test fun builtInsAndExistingNodesAreAccepted() {
        val rules = listOf(
            RuleEntity(id = 1, outbound = 0),
            RuleEntity(id = 2, outbound = -1),
            RuleEntity(id = 3, outbound = -2),
            RuleEntity(id = 4, outbound = 40),
        )

        assertEquals(emptyList<Issue>(), OutboundReferenceValidator.validate(
            rules, existingOutboundIds = setOf(40L),
        ))
    }

    @Test fun missingOutboundReportsOnlyRuleIdentityAndAnHonestReferenceKind() {
        val rule = RuleEntity(
            id = 12, name = "Work", outbound = 901,
            config = """{"action":"route","password":"fixture-password","server":"private.fixture.invalid","stderr":"fixture-stderr"}""",
            domains = "private.fixture.invalid", ip = "192.0.2.77",
        )

        val issues = OutboundReferenceValidator.validate(
            listOf(rule), existingOutboundIds = emptySet(),
        )

        assertEquals(listOf(Issue(12L, "Work", ReferenceKind.NODE_OR_STRATEGY_GROUP, 901L)), issues)
        for (sensitive in listOf("fixture-password", "private.fixture.invalid", "192.0.2.77", "fixture-stderr", "\"action\"")) {
            assertFalse("Diagnostic must omit $sensitive", issues.toString().contains(sensitive))
        }
    }

    @Test fun existingStrategyGroupOrBalancerOutboundIsAccepted() {
        val rule = RuleEntity(id = 20, name = "Group route", outbound = 100)

        assertEquals(emptyList<Issue>(), OutboundReferenceValidator.validate(
            listOf(rule), existingOutboundIds = setOf(100L),
        ))
    }

    @Test fun actionsWithoutAnOutboundIgnoreDanglingReferences() {
        for (action in listOf("reject", "hijack-dns", "sniff", "resolve", "route-options")) {
            val rule = RuleEntity(id = 30, outbound = 999, config = """{"action":"$action"}""")

            assertEquals(action, emptyList<Issue>(), OutboundReferenceValidator.validate(
                listOf(rule), existingOutboundIds = emptySet(),
            ))
        }
    }

    @Test fun blankRuleNamesFallBackToRuleAndId() {
        for (name in listOf("", " \t\n")) {
            val issues = OutboundReferenceValidator.validate(
                listOf(RuleEntity(id = 31, name = name, outbound = 999)),
                existingOutboundIds = emptySet(),
            )

            assertEquals(listOf(Issue(31L, "Rule 31", ReferenceKind.NODE_OR_STRATEGY_GROUP, 999L)), issues)
        }
    }
}
