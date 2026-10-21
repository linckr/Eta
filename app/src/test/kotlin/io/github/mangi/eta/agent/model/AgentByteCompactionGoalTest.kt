package io.github.mangi.eta.agent.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentByteCompactionGoalTest {
    @Test fun targetIsThreeQuartersForByteOrBothPressureOnly() {
        for ((trigger, budget, expected) in listOf(
            Triple("bytes", 524_288, 393_216L),
            Triple("both", 1_048_576, 786_432L),
            Triple("bytes", 2_097_152, 1_572_864L),
            Triple("bytes", 1, 0L),
            Triple("bytes", 0, null),
            Triple("token", 1_048_576, null),
            Triple("none", 524_288, null),
        )) {
            assertEquals("$trigger/$budget", expected, AgentByteCompactionGoal.forTrigger(trigger, budget).targetBytes)
        }
    }

    @Test fun intMaxBudgetUsesLongMathAndDoesNotOverflow() {
        val goal = AgentByteCompactionGoal.forTrigger("bytes", Int.MAX_VALUE)
        assertEquals(1_610_612_735L, goal.targetBytes)
        assertEquals(536_870_912L, goal.headroom(1_610_612_735L))
    }

    @Test fun statusAndHeadroomUseStrictBoundaries() {
        val goal = AgentByteCompactionGoal.forTrigger("bytes", 1_048_576)
        assertTrue(goal.applies)
        assertEquals("met", goal.status(786_432))
        assertEquals("not_met", goal.status(786_433))
        assertEquals(262_144L, goal.headroom(786_432))
        assertFalse(AgentByteCompactionGoal.forTrigger("token", 1_048_576).applies)
        assertEquals("not_applicable", AgentByteCompactionGoal.forTrigger("token", 1_048_576).status(1))
        assertNull(AgentByteCompactionGoal.forTrigger("token", 1_048_576).targetBytes)
    }
}
