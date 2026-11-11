package com.visualassistant.app.chat

import androidx.test.ext.junit.runners.AndroidJUnit4
import java.io.IOException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class LiveProtocolInstrumentedTest {
    @Test fun responseIncludesDistinctSourceTitles() {
        val raw = """{"reply":"  Use input.  ","sources":[{"title":"Guide"},{"title":"Guide"},{"title":"Manual"}]}"""
        assertEquals("Use input.\n\nSources: Guide, Manual", LiveProtocol.parseResponse(raw))
    }

    @Test fun emptyResponseIsRejected() {
        assertThrows(IOException::class.java) { LiveProtocol.parseResponse("""{"reply":" "}""") }
    }
}
