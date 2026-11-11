package com.visualassistant.app.mosslive

import java.util.UUID

internal data class MossInputTranscriptChange(
    val turnId: String,
    val text: String,
    val finalized: Boolean,
    val removeTurnId: String? = null,
)

/** Maps MOSS input transcript events onto stable timeline IDs without persisting interim captions. */
internal class MossInputTranscriptTracker(
    private val newLocalId: () -> String = { UUID.randomUUID().toString() },
) {
    private val aliases = LinkedHashMap<String, String>()
    private var pendingTurnId: String? = null
    private var lastFinalized: Pair<String, String>? = null

    fun reset() {
        aliases.clear()
        pendingTurnId = null
        lastFinalized = null
    }

    fun updated(text: String, providerTurn: String?): MossInputTranscriptChange {
        val id = providerTurn?.takeIf(String::isNotBlank)?.let { aliases[it] ?: it }
            ?: pendingTurnId ?: "pending:${newLocalId()}"
        pendingTurnId = id
        return MossInputTranscriptChange(id, text, finalized = false)
    }

    fun done(text: String, providerTurn: String?): MossInputTranscriptChange {
        val providerId = providerTurn?.takeIf(String::isNotBlank)
        val id = providerId?.let { aliases[it] ?: it }
            ?: pendingTurnId ?: "local:${newLocalId()}"
        val oldPending = pendingTurnId
        pendingTurnId = null
        if (providerId != null) aliases[providerId] = id
        lastFinalized = text to id
        return MossInputTranscriptChange(id, text, finalized = true,
            removeTurnId = oldPending?.takeIf { providerId != null && it != id })
    }

    fun revised(text: String, providerTurn: String?, previous: String): MossInputTranscriptChange? {
        val providerId = providerTurn?.takeIf(String::isNotBlank) ?: return null
        val id = if (previous.isNotBlank() && lastFinalized?.first == previous) {
            lastFinalized!!.second
        } else {
            aliases[providerId] ?: providerId
        }
        aliases[providerId] = id
        lastFinalized = text to id
        return MossInputTranscriptChange(id, text, finalized = true)
    }
}

internal fun resolveInterruptedAssistantTurn(eventTurnId: String?, activeTurnId: String?): String? =
    eventTurnId?.takeIf(String::isNotBlank) ?: activeTurnId?.takeIf(String::isNotBlank)
