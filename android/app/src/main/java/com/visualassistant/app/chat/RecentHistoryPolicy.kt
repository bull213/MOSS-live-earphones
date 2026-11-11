package com.visualassistant.app.chat

/** A logical content budget; Room and filesystem overhead are outside this limit. */
internal class RecentHistoryPolicy(
    private val limitBytes: Long,
    private val imageSize: (String) -> Long,
) {
    private data class TurnKey(val conversationId: String, val turnId: String)

    fun turnsToRemove(messages: List<MessageEntity>): List<Pair<String, String>> {
        val turns = groupTurns(messages)
        val references = messages.mapNotNull { it.imageKey }.groupingBy { it }.eachCount().toMutableMap()
        var bytes = textBytes(messages) + references.keys.sumOf(imageSize)
        val newest = turns.keys.lastOrNull()
        val removed = mutableListOf<Pair<String, String>>()
        for ((key, rows) in turns) {
            if (bytes <= limitBytes) break
            // Never discard an unfinished request or the newest turn just written.
            if (key == newest || isPending(rows)) continue
            removed += key.conversationId to key.turnId
            for (row in rows) {
                bytes -= textBytes(row)
                row.imageKey?.let { image ->
                    val remaining = references.getValue(image) - 1
                    references[image] = remaining
                    if (remaining == 0) bytes -= imageSize(image)
                }
            }
        }
        return removed
    }

    fun boundedContext(messages: List<MessageEntity>): List<MessageEntity> {
        val turns = groupTurns(messages).values.toList()
        val retained = mutableListOf<List<MessageEntity>>()
        val images = mutableSetOf<String>()
        var bytes = 0L
        for (rows in turns.asReversed()) {
            if (isPending(rows)) continue
            val newImages = imageKeys(rows) - images
            val size = textBytes(rows) + newImages.sumOf(imageSize)
            if (bytes + size > limitBytes) break
            bytes += size
            images += newImages
            retained += rows
        }
        return retained.asReversed().flatten()
    }

    private fun groupTurns(messages: List<MessageEntity>) =
        messages.groupBy { TurnKey(it.conversationId, it.turnId) }

    private fun imageKeys(messages: List<MessageEntity>) = messages.mapNotNull { it.imageKey }.toSet()

    private fun textBytes(messages: List<MessageEntity>) = messages.sumOf { textBytes(it) }

    private fun textBytes(message: MessageEntity) = message.text.toByteArray(Charsets.UTF_8).size.toLong()

    private fun isPending(messages: List<MessageEntity>) = messages.any { it.status == ChatStatus.SENDING.name }
}
