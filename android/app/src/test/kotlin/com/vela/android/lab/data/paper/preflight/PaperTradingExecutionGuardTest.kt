package com.vela.android.lab.data.paper.preflight

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestFactory

class PaperTradingExecutionGuardTest {

    @Test
    fun `canExecuteOrders is hard-coded false`() {
        assertFalse(PaperTradingExecutionGuard.canExecuteOrders)
    }

    @Test
    fun `rationale clearly states no execution surface`() {
        assertTrue(
            PaperTradingExecutionGuard.rationale.lowercase().contains("no execution"),
            "rationale was: '${PaperTradingExecutionGuard.rationale}'",
        )
    }

    @TestFactory
    fun `PaperTradingExecutionGuard declares no execution methods`(): List<DynamicTest> {
        val forbidden = listOf(
            "submitorder", "placeorder", "place_order", "buyorder", "sellorder",
            "executeorder", "executetrade", "cancelorder", "replaceorder",
            "openposition", "closeposition", "deletepositions",
            "trading", "withdraw", "deposit", "transferfund",
            // HTTP verbs that would imply mutation surface:
            "post", "put", "patch", "delete",
        )
        val methods = PaperTradingExecutionGuard::class.java.declaredMethods
            .map { it.name }
            .filterNot { it.contains('$') }
        return methods.map { name ->
            DynamicTest.dynamicTest("$name has no forbidden substring") {
                val lower = name.lowercase()
                for (bad in forbidden) {
                    assertFalse(
                        lower.contains(bad),
                        "Guard method '$name' contains forbidden substring '$bad'",
                    )
                }
            }
        }
    }

    @Test
    fun `guard exposes no field that could re-enable execution`() {
        // The companion-object getters that survive on the JVM Kotlin
        // object are the getters for `canExecuteOrders` and `rationale`.
        // Anything else would be suspicious.
        val getters = PaperTradingExecutionGuard::class.java.declaredMethods
            .map { it.name }
            .filter { it.startsWith("get") || it.startsWith("is") }
            .toSet()
        // Whitelist: synthetic accessors for the two documented constants.
        val expected = setOf<String>()  // const vals are compiled to static fields, no getter
        assertEquals(expected, getters, "Unexpected accessor methods: $getters")
    }
}
