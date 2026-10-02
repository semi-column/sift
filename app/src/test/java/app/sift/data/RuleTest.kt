package app.sift.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RuleTest {
    private val keep = Rule(1, "Keep delivery", listOf("delivery code"), "shop.app", Category.ORDERS, RuleAction.ALLOW)

    @Test
    fun keepWinsOverRemovalRegardlessOfOrder() {
        val remove = keep.copy(id = 2, action = RuleAction.DISMISS)
        for (rules in listOf(listOf(remove, keep), listOf(keep, remove))) {
            assertEquals(keep, matchingRule(rules, "shop.app", Category.ORDERS, "Your DELIVERY CODE is 1234"))
        }
    }

    @Test
    fun enforcesAppCategoryAndEnabledScope() {
        assertNull(matchingRule(listOf(keep), "other.app", Category.ORDERS, "delivery code"))
        assertNull(matchingRule(listOf(keep), "shop.app", Category.PROMO, "delivery code"))
        assertNull(matchingRule(listOf(keep.copy(enabled = false)), "shop.app", Category.ORDERS, "delivery code"))
        assertNull(matchingRule(listOf(keep), "shop.app", Category.ORDERS, "sale"))
    }

    @Test
    fun unscopedRuleMatchesAnyAppAndCategory() {
        val global = keep.copy(pkg = null, category = null)
        assertEquals(global, matchingRule(listOf(global), "other.app", Category.PROMO, "delivery code"))
    }
}