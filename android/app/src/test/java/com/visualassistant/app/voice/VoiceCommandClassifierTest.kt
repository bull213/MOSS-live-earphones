package com.visualassistant.app.voice

import com.visualassistant.app.network.NotebookItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class VoiceCommandClassifierTest {

    private val notebooks = listOf(
        NotebookItem(id = "nb-1", title = "Neuroscience"),
        NotebookItem(id = "nb-2", title = "Car Maintenance"),
        NotebookItem(id = "nb-3", title = "Car Maintenance and Repair"),
        NotebookItem(id = "nb-4", title = "Biology"),
    )

    @Test
    fun testSingleCommandSwitchWithoutQuery() {
        val result = VoiceCommandClassifier.classify("Use notebook Neuroscience", notebooks)
        assertTrue(result is SpokenCommand.SwitchNotebook)
        val cmd = result as SpokenCommand.SwitchNotebook
        assertEquals("nb-1", cmd.notebook.id)
        assertEquals("Neuroscience", cmd.notebook.title)
        assertNull(cmd.query)
    }

    @Test
    fun testCompoundQueryPreservesInterrogatives() {
        val result = VoiceCommandClassifier.classify(
            "Use notebook Neuroscience what is the dopamine pathway?",
            notebooks
        )
        assertTrue(result is SpokenCommand.SwitchNotebook)
        val cmd = result as SpokenCommand.SwitchNotebook
        assertEquals("nb-1", cmd.notebook.id)
        assertEquals("what is the dopamine pathway?", cmd.query)
    }

    @Test
    fun testCompoundQueryWithPunctuationDelimiters() {
        val result = VoiceCommandClassifier.classify(
            "Use notebook Neuroscience, how does synaptic plasticity work?",
            notebooks
        )
        assertTrue(result is SpokenCommand.SwitchNotebook)
        val cmd = result as SpokenCommand.SwitchNotebook
        assertEquals("nb-1", cmd.notebook.id)
        assertEquals("how does synaptic plasticity work?", cmd.query)
    }

    @Test
    fun testMultiWordNotebookTitleWithAnd() {
        val result = VoiceCommandClassifier.classify(
            "Switch to notebook Car Maintenance and Repair",
            notebooks
        )
        assertTrue(result is SpokenCommand.SwitchNotebook)
        val cmd = result as SpokenCommand.SwitchNotebook
        assertEquals("nb-3", cmd.notebook.id)
        assertEquals("Car Maintenance and Repair", cmd.notebook.title)
        assertNull(cmd.query)
    }

    @Test
    fun testLongestTitleMatching() {
        val compoundOnShorter = VoiceCommandClassifier.classify(
            "Use notebook Car Maintenance how to change oil?",
            notebooks
        )
        assertTrue(compoundOnShorter is SpokenCommand.SwitchNotebook)
        val shorterCmd = compoundOnShorter as SpokenCommand.SwitchNotebook
        assertEquals("nb-2", shorterCmd.notebook.id)
        assertEquals("how to change oil?", shorterCmd.query)

        val compoundOnLonger = VoiceCommandClassifier.classify(
            "Use notebook Car Maintenance and Repair explain brake bleeding",
            notebooks
        )
        assertTrue(compoundOnLonger is SpokenCommand.SwitchNotebook)
        val longerCmd = compoundOnLonger as SpokenCommand.SwitchNotebook
        assertEquals("nb-3", longerCmd.notebook.id)
        assertEquals("explain brake bleeding", longerCmd.query)
    }

    @Test
    fun testInexactPartialTitleMatching() {
        // Partial title "Car Repair" should match "Car Maintenance and Repair"
        val result = VoiceCommandClassifier.classify("Use notebook Repair", notebooks)
        assertTrue(result is SpokenCommand.SwitchNotebook)
        val cmd = result as SpokenCommand.SwitchNotebook
        assertEquals("nb-3", cmd.notebook.id)
        assertEquals("Car Maintenance and Repair", cmd.notebook.title)

        // Substring title with question
        val compound = VoiceCommandClassifier.classify("Switch to notebook Repair how to fix caliper?", notebooks)
        assertTrue(compound is SpokenCommand.SwitchNotebook)
        val compoundCmd = compound as SpokenCommand.SwitchNotebook
        assertEquals("nb-3", compoundCmd.notebook.id)
        assertEquals("how to fix caliper?", compoundCmd.query)
    }

    @Test
    fun testUnknownNotebookEmitsNotebookNotFound() {
        val result = VoiceCommandClassifier.classify("Use notebook Quantum Physics", notebooks)
        assertTrue(result is SpokenCommand.NotebookNotFound)
        val cmd = result as SpokenCommand.NotebookNotFound
        assertEquals("Quantum Physics", cmd.spokenTitle)
    }

    @Test
    fun testUnknownNotebookWithQuestionEmitsNotebookNotFound() {
        val result = VoiceCommandClassifier.classify(
            "Use notebook Quantum Physics, what is entanglement?",
            notebooks
        )
        assertTrue(result is SpokenCommand.NotebookNotFound)
        val cmd = result as SpokenCommand.NotebookNotFound
        assertEquals("Quantum Physics", cmd.spokenTitle)
    }

    @Test
    fun testBackToMossWithCourtesyTokens() {
        assertEquals(SpokenCommand.BackToMoss, VoiceCommandClassifier.classify("Back to MOSS", notebooks))
        assertEquals(SpokenCommand.BackToMoss, VoiceCommandClassifier.classify("Back to MOSS please", notebooks))
        assertEquals(SpokenCommand.BackToMoss, VoiceCommandClassifier.classify("switch to moss.", notebooks))
        assertEquals(SpokenCommand.BackToMoss, VoiceCommandClassifier.classify("return to moss!", notebooks))
        assertEquals(SpokenCommand.BackToMoss, VoiceCommandClassifier.classify("go back to moss", notebooks))
    }

    @Test
    fun testSummarizeNowVariations() {
        assertEquals(SpokenCommand.SummarizeNow, VoiceCommandClassifier.classify("Summarize now", notebooks))
        assertEquals(SpokenCommand.SummarizeNow, VoiceCommandClassifier.classify("summarize now.", notebooks))
        assertEquals(SpokenCommand.SummarizeNow, VoiceCommandClassifier.classify("give me a summary", notebooks))
        assertEquals(SpokenCommand.SummarizeNow, VoiceCommandClassifier.classify("summarize this notebook", notebooks))
    }

    @Test
    fun testPlainConversationReturnsNormalSpeech() {
        val result = VoiceCommandClassifier.classify("Hello MOSS, what is the weather today?", notebooks)
        assertEquals(SpokenCommand.NormalSpeech("Hello MOSS, what is the weather today?"), result)
    }

    @Test
    fun testHasCommandPrefix() {
        assertTrue(VoiceCommandClassifier.hasCommandPrefix("Use notebook Neuroscience"))
        assertTrue(VoiceCommandClassifier.hasCommandPrefix("switch to notebook Biology"))
        assertTrue(VoiceCommandClassifier.hasCommandPrefix("open notebook Notes"))
        assertTrue(VoiceCommandClassifier.hasCommandPrefix("check notebook Docs"))
        assertTrue(VoiceCommandClassifier.hasCommandPrefix("back to moss"))
        assertTrue(VoiceCommandClassifier.hasCommandPrefix("summarize now"))
        assertFalse(VoiceCommandClassifier.hasCommandPrefix("I would like to tell you a story about my notebook"))
        assertFalse(VoiceCommandClassifier.hasCommandPrefix("What is the meaning of life?"))
    }
}
