package com.visualassistant.app.voice

/** Join model segments; remove repeated boundary words only when their times overlap. */
internal fun combineTranscriptLines(lines: List<RecognitionEvent.Completed>): String {
    val result = mutableListOf<String>()
    var previousEnd = Float.NEGATIVE_INFINITY
    for (line in lines) {
        val right = line.text.trim()
        if (right.isEmpty()) continue
        val words = right.split(WHITESPACE)
        var repeated = 0
        if (line.start < previousEnd) {
            for (count in 1..minOf(6, result.size, words.size)) {
                val left = result.takeLast(count).map(::normalized)
                if (left.all { it.isNotEmpty() } && left == words.take(count).map(::normalized)) repeated = count
            }
        }
        result.addAll(words.drop(repeated))
        previousEnd = maxOf(previousEnd, line.start + line.duration)
    }
    return result.joinToString(" ")
}

private val WHITESPACE = Regex("\\s+")
private fun normalized(word: String) = word.lowercase().trim { !it.isLetterOrDigit() }
