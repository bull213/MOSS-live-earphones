package com.visualassistant.app.voice

import org.junit.Assert.assertEquals
import org.junit.Test

class TtsTextSanitizerTest {
    @Test
    fun sanitizesMarkdownHeadersAndFormatting() {
        val input = """
            # Microwave Instructions
            To heat your meal:
            * Press **Start** button.
            * Set time to _2 minutes_.
            Enjoy your meal!
        """.trimIndent()
        val expected = "Microwave Instructions To heat your meal: Press Start button. Set time to 2 minutes. Enjoy your meal!"
        assertEquals(expected, TtsTextSanitizer.sanitize(input))
    }

    @Test
    fun removesCitationBrackets() {
        val input = "Press the power button [1]. Wait for 5 seconds [2, 3] before opening the door [4]."
        val expected = "Press the power button . Wait for 5 seconds before opening the door ."
        assertEquals("Press the power button . Wait for 5 seconds before opening the door .", TtsTextSanitizer.sanitize(input))
    }

    @Test
    fun removesNumberedListsAndBackticks() {
        val input = """
            1. Select `Auto Cook` mode.
            2. Turn dial clockwise.
        """.trimIndent()
        val expected = "Select Auto Cook mode. Turn dial clockwise."
        assertEquals(expected, TtsTextSanitizer.sanitize(input))
    }

    @Test
    fun handlesEmptyOrWhitespaceString() {
        assertEquals("", TtsTextSanitizer.sanitize(""))
        assertEquals("", TtsTextSanitizer.sanitize("   \n\t  "))
    }
}
