package io.github.mangi.eta.hook.breeno

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BreenoAsrResultIdentityTest {
    private val stream = listOf("SpeechRecognizer" to "VadControl", "SpeechRecognizer" to "StreamRecognizeResult")
    @Test fun exactCurrentRequestAcceptsBothResultFormats() {
        assertTrue(BreenoAsrResultIdentity.accepts("room", "new", "room", "new", stream))
        assertTrue(BreenoAsrResultIdentity.accepts("room", "new", "room", "new", listOf("SpeechRecognizer" to "RecognizeResult")))
    }
    @Test fun delayedOldResultsAndOtherRoomsStayRejected() {
        assertFalse(BreenoAsrResultIdentity.accepts("room", "new", "room", "old", stream))
        assertFalse(BreenoAsrResultIdentity.accepts("room", "new", "other", "new", stream))
        assertFalse(BreenoAsrResultIdentity.accepts("room", null, "room", "", stream))
    }
    @Test fun unrelatedOrMixedActionsStayRejected() {
        assertFalse(BreenoAsrResultIdentity.accepts("room", "new", "room", "new", emptyList()))
        assertFalse(BreenoAsrResultIdentity.accepts("room", "new", "room", "new", listOf("SpeechRecognizer" to "VadControl")))
        assertFalse(BreenoAsrResultIdentity.accepts("room", "new", "room", "new", stream + ("PhoneCall" to "Dial")))
        assertFalse(BreenoAsrResultIdentity.accepts("room", "new", "room", "new", stream + ("SpeechRecognizer" to "ExpectSpeech")))
    }
}
