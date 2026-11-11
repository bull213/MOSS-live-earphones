package com.visualassistant.app.mosslive

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PendingAudioBudgetTest {
    @Test fun acceptsBatchedAudioChunksUntilByteLimitAndReleasesPlayedAudio() {
        val budget = PendingAudioBudget(6 * 1024 * 1024)
        repeat(22) { assertTrue(budget.reserve(32 * 1024)) }
        assertFalse(budget.reserve(6 * 1024 * 1024))
        repeat(22) { budget.release(32 * 1024) }
        assertTrue(budget.reserve(6 * 1024 * 1024))
    }
}
