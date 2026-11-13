package com.visualassistant.app.voice

import com.visualassistant.app.network.NotebookItem

sealed interface SpokenCommand {
    data class SwitchNotebook(val notebook: NotebookItem, val query: String?) : SpokenCommand
    data class NotebookNotFound(val spokenTitle: String) : SpokenCommand
    data object BackToMoss : SpokenCommand
    data object SummarizeNow : SpokenCommand
    data class NormalSpeech(val text: String) : SpokenCommand
}

object VoiceCommandClassifier {
    private val SWITCH_PREFIX = Regex("^(?:use|switch to|open|check)\\s+(?:notebook\\s+)?", RegexOption.IGNORE_CASE)
    private val BACK_TO_MOSS = Regex("^(?:back to|switch to|return to|go back to)\\s+moss\\b.*$", RegexOption.IGNORE_CASE)
    private val SUMMARIZE_NOW = Regex("^(?:summarize now|summarize|give me a summary|summarize this notebook)\\b.*$", RegexOption.IGNORE_CASE)
    private val QUESTION_DELIMITERS = Regex("(?i)[,:?\\n]|\\s+(?:what|how|why|can|tell|explain)\\b")

    fun hasCommandPrefix(transcript: String): Boolean {
        val trimmed = transcript.trim()
        return SWITCH_PREFIX.containsMatchIn(trimmed) ||
               BACK_TO_MOSS.containsMatchIn(trimmed) ||
               SUMMARIZE_NOW.containsMatchIn(trimmed)
    }

    fun classify(transcript: String, availableNotebooks: List<NotebookItem>): SpokenCommand {
        val text = transcript.trim()
        if (text.isEmpty()) return SpokenCommand.NormalSpeech("")
        if (BACK_TO_MOSS.matches(text)) return SpokenCommand.BackToMoss
        if (SUMMARIZE_NOW.matches(text)) return SpokenCommand.SummarizeNow

        val prefixMatch = SWITCH_PREFIX.find(text)
        if (prefixMatch != null) {
            val remainder = text.substring(prefixMatch.range.last + 1).trim()
            if (remainder.isNotEmpty()) {
                // Sort notebooks by title length descending to match longest candidate title first
                val sortedNotebooks = availableNotebooks.sortedByDescending { it.title.length }
                // 1. Exact or prefix match (longest title first)
                for (nb in sortedNotebooks) {
                    if (remainder.startsWith(nb.title, ignoreCase = true)) {
                        val postTitle = remainder.substring(nb.title.length).trimStart(' ', ',', ':', '-', '?')
                        val query = if (postTitle.isNotBlank()) postTitle else null
                        return SpokenCommand.SwitchNotebook(nb, query)
                    }
                }

                // 2. Inexact / partial candidate title match without requiring 100% complete accuracy
                val candidateTitle = remainder.split(QUESTION_DELIMITERS)[0].trim()
                val postCandidate = if (candidateTitle.isNotEmpty() && remainder.length > candidateTitle.length) {
                    remainder.substring(candidateTitle.length).trimStart(' ', ',', ':', '-', '?')
                } else null

                if (candidateTitle.isNotEmpty()) {
                    val candidateLower = candidateTitle.lowercase()
                    val candidateTokens = candidateLower.split(Regex("[^\\w\\u0590-\\u05fe]+")).filter { it.length >= 3 }

                    val partialMatches = sortedNotebooks.filter { nb ->
                        val titleLower = nb.title.lowercase()
                        titleLower.contains(candidateLower) ||
                        candidateLower.contains(titleLower) ||
                        (candidateTokens.isNotEmpty() && candidateTokens.count { titleLower.contains(it) } >= 2) ||
                        (candidateTokens.size == 1 && candidateTokens.first().let { it.length >= 4 && titleLower.contains(it) })
                    }

                    if (partialMatches.isNotEmpty()) {
                        val prefixCandidate = partialMatches.firstOrNull { it.title.lowercase().startsWith(candidateLower) }
                        val chosen = prefixCandidate ?: partialMatches.first()
                        val query = if (!postCandidate.isNullOrBlank()) postCandidate else null
                        return SpokenCommand.SwitchNotebook(chosen, query)
                    }
                }

                // Spoken title was not matched in catalog
                return SpokenCommand.NotebookNotFound(candidateTitle.ifBlank { remainder })
            }
        }

        return SpokenCommand.NormalSpeech(text)
    }
}
