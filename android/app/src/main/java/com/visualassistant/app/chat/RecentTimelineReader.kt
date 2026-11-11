package com.visualassistant.app.chat

internal data class RecentTimelineRows(
    val messages: List<MessageEntity>,
    val displayLimited: Boolean,
    val canLoadMore: Boolean,
)

/** Reads recent turns backwards in bounded pages; callers hold the repository lock. */
internal class RecentTimelineReader(
    private val dao: ConversationDao,
    private val limitBytes: Long,
    private val imageSize: (String) -> Long,
) {
    suspend fun read(maxMessages: Int): RecentTimelineRows {
        val turns = mutableListOf<List<MessageEntity>>()
        val images = mutableSetOf<String>()
        val pending = mutableListOf<MessageEntity>()
        var before = Long.MAX_VALUE
        var bytes = 0L
        var count = 0
        var limited = false
        var canLoadMore = false

        fun acceptTurn(): Boolean {
            if (pending.isEmpty()) return true
            val keys = pending.mapNotNull { it.imageKey }.toSet() - images
            val size = pending.sumOf { it.text.toByteArray(Charsets.UTF_8).size.toLong() } + keys.sumOf(imageSize)
            if (turns.isNotEmpty() && bytes + size > limitBytes) {
                limited = true
                return false
            }
            if (turns.isNotEmpty() && count + pending.size > maxMessages) {
                limited = true
                canLoadMore = true
                return false
            }
            bytes += size
            count += pending.size
            images += keys
            turns += pending.asReversed().toList()
            pending.clear()
            return true
        }

        var done = false
        while (!done) {
            val page = dao.messagesBefore(before, PAGE_SIZE)
            if (page.isEmpty()) break
            for (row in page) {
                val previous = pending.firstOrNull()
                if (previous != null && (previous.turnId != row.turnId || previous.conversationId != row.conversationId)) {
                    if (!acceptTurn()) { done = true; break }
                }
                pending += row
            }
            before = page.last().sequence
            if (page.size < PAGE_SIZE) break
        }
        if (!done) acceptTurn()
        return RecentTimelineRows(turns.asReversed().flatten(), limited, canLoadMore)
    }

    private companion object { const val PAGE_SIZE = 200 }
}
