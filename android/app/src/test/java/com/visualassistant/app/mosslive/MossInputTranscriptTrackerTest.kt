package com.visualassistant.app.mosslive

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MossInputTranscriptTrackerTest {
    @Test
    fun interimCaptionIsTemporaryAndConfirmedTurnUsesProviderId() {
        val tracker = MossInputTranscriptTracker { "generated" }
        val interim = tracker.updated("What is two", providerTurn = null)
        assertFalse(interim.finalized)
        assertEquals("pending:generated", interim.turnId)

        val done = tracker.done("What is two plus two?", providerTurn = "user-turn-1")
        assertTrue(done.finalized)
        assertEquals("user-turn-1", done.turnId)
        assertEquals("pending:generated", done.removeTurnId)
    }

    @Test
    fun missingOptionalProviderTurnGetsLocalIdAndLaterRevisionReusesIt() {
        val tracker = MossInputTranscriptTracker { "generated" }
        val interim = tracker.updated("Turn this light on", providerTurn = null)
        val done = tracker.done("Turn this light on", providerTurn = null)
        assertEquals(interim.turnId, done.turnId)
        assertNull(done.removeTurnId)

        val revised = tracker.revised("Turn the light on", "provider-revision-id", "Turn this light on")!!
        assertEquals(done.turnId, revised.turnId)
        assertTrue(revised.finalized)
        assertEquals("Turn the light on", revised.text)

        val revisedAgain = tracker.revised("Turn the light on, please", "provider-revision-id", "Turn the light on")!!
        assertEquals(done.turnId, revisedAgain.turnId)
    }

    @Test
    fun confirmedTurnAndRevisionKeepTheSameId() {
        val tracker = MossInputTranscriptTracker { "generated" }
        tracker.updated("What is two", providerTurn = null)
        val done = tracker.done("What is two plus two?", providerTurn = "user-turn-1")
        assertEquals("user-turn-1", done.turnId)

        val revised = tracker.revised("What is 2 plus 2?", "user-turn-1", "What is two plus two?")!!
        assertEquals("user-turn-1", revised.turnId)
        assertEquals("What is 2 plus 2?", revised.text)
    }

    @Test
    fun interruptedTurnUsesEventIdOrActiveResponseFallback() {
        assertEquals("event-turn", resolveInterruptedAssistantTurn("event-turn", "active-turn"))
        assertEquals("active-turn", resolveInterruptedAssistantTurn(null, "active-turn"))
        assertNull(resolveInterruptedAssistantTurn(null, null))
    }
}
