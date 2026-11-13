package com.visualassistant.app.voice

object TtsTextSanitizer {
    private val HEADING_REGEX = Regex("""^#{1,6}\s+""", RegexOption.MULTILINE)
    private val CITATION_REGEX = Regex("""\[\d+(?:,\s*\d+)*\]""")
    private val BOLD_DOUBLE_ASTERISK = Regex("""\*\*([^*]+)\*\*""")
    private val ITALIC_SINGLE_ASTERISK = Regex("""\*([^*]+)\*""")
    private val BOLD_DOUBLE_UNDERSCORE = Regex("""__([^_]+)__""")
    private val ITALIC_SINGLE_UNDERSCORE = Regex("""_([^_]+)_""")
    private val BULLET_POINT_REGEX = Regex("""^[\*\-\+]\s+""", RegexOption.MULTILINE)
    private val NUMBERED_LIST_REGEX = Regex("""^\d+\.\s+""", RegexOption.MULTILINE)
    private val BACKTICK_REGEX = Regex("""`([^`]+)`""")
    private val WHITESPACE_REGEX = Regex("""\s+""")

    fun sanitize(input: String): String {
        if (input.isBlank()) return ""
        return input
            .replace(HEADING_REGEX, "")
            .replace(CITATION_REGEX, "")
            .replace(BOLD_DOUBLE_ASTERISK, "$1")
            .replace(ITALIC_SINGLE_ASTERISK, "$1")
            .replace(BOLD_DOUBLE_UNDERSCORE, "$1")
            .replace(ITALIC_SINGLE_UNDERSCORE, "$1")
            .replace(BULLET_POINT_REGEX, "")
            .replace(NUMBERED_LIST_REGEX, "")
            .replace(BACKTICK_REGEX, "$1")
            .replace(WHITESPACE_REGEX, " ")
            .trim()
    }
}
