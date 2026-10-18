package io.github.mangi.eta.hook.breeno

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BreenoAsrProbeGateTest {
    @Test fun onlyObservedRequestIsConsumed() {
        val gate = BreenoAsrProbeGate()
        gate.arm("room", 100)
        assertFalse(gate.matches("room", listOf("first"), 1))
        gate.observe("room", listOf("first", "original"), 2)
        assertTrue(gate.matches("room", listOf("original"), 3))
        assertFalse(gate.matches("other-room", listOf("first"), 3))
        gate.observe("room", listOf("second"), 4)
        assertFalse(gate.matches("room", listOf("second"), 5))
        assertFalse(gate.matches("room", listOf("first"), 100))
    }

    @Test fun blankIdsAndRearmingCannotConsumeOldRequest() {
        val gate = BreenoAsrProbeGate()
        gate.arm("room", 100)
        gate.observe("room", listOf(null, ""), 1)
        assertFalse(gate.matches("room", listOf(""), 2))
        gate.observe("room", listOf("old"), 3)
        gate.arm("room", 200)
        assertFalse(gate.matches("room", listOf("old"), 4))
        gate.observe("room", listOf("new"), 5)
        assertTrue(gate.matches("room", listOf("new"), 6))
    }
}
