package com.visualassistant.app.voice

import org.junit.Assert.assertEquals
import org.junit.Test

class CombineTranscriptLinesTest {
    @Test fun removesOnlyOverlappingBoundaryWords() {
        val lines = listOf(
            RecognitionEvent.Completed(1, "Find the red button", 0f, 15f),
            RecognitionEvent.Completed(2, "red button on the television", 14.8f, 3f),
            RecognitionEvent.Completed(3, "television again", 20f, 1f),
        )
        assertEquals("Find the red button on the television television again", combineTranscriptLines(lines))
    }

    @Test fun ignoresEmptySegments() {
        val lines = listOf(
            RecognitionEvent.Completed(1, "", 0f, 2f),
            RecognitionEvent.Completed(2, "Hello world.", 3f, 1f),
            RecognitionEvent.Completed(3, "   ", 4f, 1f),
        )
        assertEquals("Hello world.", combineTranscriptLines(lines))
    }
}
