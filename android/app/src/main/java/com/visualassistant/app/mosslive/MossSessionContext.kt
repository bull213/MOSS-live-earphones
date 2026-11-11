package com.visualassistant.app.mosslive

import com.visualassistant.app.camera.CapturedPhoto
import com.visualassistant.app.chat.ChatKind
import com.visualassistant.app.chat.ChatMessage
import com.visualassistant.app.chat.ChatSource
import com.visualassistant.app.chat.ChatStatus

/** Bounded reference context and recent saved photos prepared by the chat layer. */
internal data class MossSessionContext(val instructions: String = "", val photos: List<ByteArray> = emptyList()) {
    companion object {
        const val MAX_PHOTOS = 3
        const val MAX_INSTRUCTIONS_CHARS = 4_000

        fun from(
            messages: List<ChatMessage>,
            stagedPhoto: CapturedPhoto? = null,
            encodeCapturedPhoto: (CapturedPhoto) -> ByteArray?,
            encodeMessagePhoto: (ChatMessage) -> ByteArray? = { msg -> msg.photo?.let(encodeCapturedPhoto) },
        ): MossSessionContext {
            val eligible = messages.filter { it.status == ChatStatus.READY }
            val text = eligible.mapNotNull { message ->
                val role = when {
                    message.source == ChatSource.ASSISTANT || message.kind == ChatKind.ASSISTANT -> "Assistant"
                    message.source == ChatSource.PHONE -> "User"
                    else -> return@mapNotNull null
                }
                val body = when {
                    message.kind == ChatKind.PHOTO -> {
                        val caption = message.text.trim()
                        if (caption.isNotEmpty()) "[Shared a photo: $caption]" else "[Shared a photo]"
                    }
                    message.text.isNotBlank() -> message.text.trim()
                    else -> return@mapNotNull null
                }.take(1_000)
                "$role: $body"
            }.takeLast(8).joinToString("\n").takeLast(MAX_INSTRUCTIONS_CHARS)

            val photos = mutableListOf<ByteArray>()
            var stagedIncluded = false

            if (stagedPhoto != null) {
                val stagedBytes = encodeCapturedPhoto(stagedPhoto)
                if (stagedBytes != null) {
                    photos.add(stagedBytes)
                    stagedIncluded = true
                }
            }

            val remainingSlots = MAX_PHOTOS - photos.size
            if (remainingSlots > 0) {
                val historicalPhotos = eligible.asReversed().asSequence()
                    .filter { it.kind == ChatKind.PHOTO && it.photo != null }
                    .filter { stagedPhoto == null || it.photo?.id != stagedPhoto.id }
                    .mapNotNull(encodeMessagePhoto)
                    .take(remainingSlots)
                    .toList()
                photos.addAll(historicalPhotos)
            }

            val instructions = buildString {
                if (text.isNotEmpty()) {
                    append("Recent chat history for reference; do not repeat it as new dialogue:\n$text")
                }
                if (stagedIncluded) {
                    if (isNotEmpty()) append('\n')
                    append("A current user photo is attached as primary visual context and active in your camera feed. Refer to what is visible in this photo when answering the user.")
                } else if (photos.isNotEmpty()) {
                    if (isNotEmpty()) append('\n')
                    append("The latest photo from the user's recent chat history is active in your live camera video stream. Refer to what is visible in this photo when answering the user.")
                }
            }

            return MossSessionContext(
                instructions = instructions,
                photos = photos,
            )
        }

        fun from(
            messages: List<ChatMessage>,
            encodeMessagePhoto: (ChatMessage) -> ByteArray?,
        ): MossSessionContext = from(
            messages = messages,
            stagedPhoto = null,
            encodeCapturedPhoto = { null },
            encodeMessagePhoto = encodeMessagePhoto,
        )
    }
}
