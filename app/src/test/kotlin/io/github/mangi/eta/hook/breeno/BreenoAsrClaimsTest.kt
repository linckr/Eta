package io.github.mangi.eta.hook.breeno

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BreenoAsrClaimsTest {
    @Test fun newRecordingAndLateOldResultStaySeparate() {
        val claims = BreenoAsrClaims()
        claims.open("old", "room", 100)
        assertTrue(claims.bind("old", "room", listOf("record-old"), 1))
        claims.open("new", "room", 200)
        assertTrue(claims.bind("new", "room", listOf("record-new"), 2))
        assertFalse(claims.bind("new", "room", listOf("record-old"), 3))
        assertTrue(claims.matches("room", listOf("record-old"), 4))
        assertTrue(claims.matches("room", listOf("record-new"), 4))
        assertFalse(claims.matches("another", listOf("record-new"), 4))
        assertFalse(claims.matches("room", listOf("record-old"), 100))
        assertTrue(claims.matches("room", listOf("record-new"), 100))
    }
    @Test fun missingAndUnrelatedRecordsCannotClaimARequest() {
        val claims = BreenoAsrClaims()
        claims.open("request", "room", 100)
        assertFalse(claims.bind("request", "room", listOf("", null), 1))
        assertFalse(claims.bind("request", "another", listOf("record"), 2))
        assertTrue(claims.bind("request", "room", listOf("record"), 3))
        assertTrue(claims.bind("request", "room", listOf("record", "alias"), 4))
        assertTrue(claims.matches("room", listOf("alias"), 5))
        assertFalse(claims.matches("room", listOf("other"), 5))
    }
    @Test fun claimHistoryIsBounded() {
        val claims = BreenoAsrClaims()
        repeat(9) { index ->
            claims.open("$index", "room", 100)
            claims.bind("$index", "room", listOf("record-$index"), 1)
        }
        assertFalse(claims.matches("room", listOf("record-0"), 2))
        assertTrue(claims.matches("room", listOf("record-8"), 2))
    }
}
