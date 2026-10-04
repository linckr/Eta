package io.github.mangi.eta.agent.voice

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BreenoSpeechTranscriptTest {
    @Test fun finishPreservesCompletedSegmentsAndCurrentPreview() {
        val transcript = BreenoSpeechTranscript()
        transcript.beginSegment("first")
        transcript.final("first", "第一句话")
        transcript.beginSegment("second")
        transcript.partial("second", "第二句话")
        transcript.finish()
        assertFalse(transcript.beginSegment("must-not-restart"))
        transcript.close()
        assertEquals("第一句话\n第二句话", transcript.text)
        assertFalse(transcript.final("second", "迟到结果"))
    }

    @Test fun finalWhileFinishingReplacesPreviewWithoutDuplication() {
        val transcript = BreenoSpeechTranscript()
        transcript.beginSegment("current")
        transcript.partial("current", "未完成")
        transcript.finish()
        assertTrue(transcript.final("current", "完整结果"))
        assertEquals("完整结果", transcript.text)
        assertFalse(transcript.final("current", "完整结果"))
        assertEquals("完整结果", transcript.text)
    }

    @Test fun lateCallbacksFromPreviousSegmentCannotChangeCurrentText() {
        val transcript = BreenoSpeechTranscript()
        transcript.beginSegment("old")
        transcript.final("old", "旧段")
        transcript.beginSegment("new")
        transcript.partial("new", "新段")
        assertFalse(transcript.partial("old", "迟到预览"))
        assertFalse(transcript.final("old", "迟到终态"))
        assertEquals("旧段\n新段", transcript.text)
    }

    @Test fun silentFreshSegmentDoesNotDiscardPreviousText() {
        val transcript = BreenoSpeechTranscript()
        transcript.beginSegment("spoken")
        transcript.final("spoken", "保留文字")
        transcript.beginSegment("silent")
        transcript.finish()
        transcript.close()
        assertEquals("保留文字", transcript.text)
        assertFalse(transcript.beginSegment("restart"))
    }

    @Test fun emptyFinalKeepsAlreadyRecognizedPreview() {
        val transcript = BreenoSpeechTranscript()
        transcript.beginSegment("current")
        transcript.partial("current", "已识别文字")
        assertTrue(transcript.final("current", ""))
        assertEquals("已识别文字", transcript.text)
    }
}
