package com.visualassistant.app.mosslive

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MossLiveDiagnosticsTest {
    @Test
    fun summaryContainsOnlyKnownEventNamesAndFrameCounts() {
        val diagnostics = MossLiveDiagnostics()
        diagnostics.recordEvent("input.transcript.done")
        diagnostics.recordEvent("output.transcript.done")
        diagnostics.recordEvent("user transcript that must not be logged\nerror")
        diagnostics.recordUplinkPcmFrame()
        diagnostics.recordUplinkPcmFrame()
        diagnostics.recordDownlinkAudioChunk(8)
        diagnostics.recordControl("""{"action":"playback.chunk.completed"}""", accepted = true)
        diagnostics.recordControl("""{"action":"playback.completed"}""", accepted = false)

        val summary = diagnostics.summary()
        assertTrue(summary.contains("session="))
        assertTrue(summary.contains("elapsed_ms="))
        assertTrue(summary.contains("shutdown=unknown"))
        assertTrue(summary.contains("events=input.transcript.done:1,other:1,output.transcript.done:1"))
        assertTrue(summary.contains("controls_created=playback.chunk.completed:1,playback.completed:1"))
        assertTrue(summary.contains("controls_accepted=playback.chunk.completed:1"))
        assertTrue(summary.contains("controls_rejected=playback.completed:1"))
        assertTrue(summary.contains("uplink_pcm_frames=2"))
        assertTrue(summary.contains("downlink_audio_chunks=1; downlink_audio_bytes=8"))
        assertFalse(summary.contains("user transcript that must not be logged"))
    }
}
