package com.visualassistant.app.chat

import android.graphics.Bitmap
import android.graphics.Color
import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import androidx.test.platform.app.InstrumentationRegistry
import com.visualassistant.app.camera.CapturedPhoto
import java.io.File
import java.time.Instant
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class ConversationRepositoryTest {
    @Test fun mossTurnPersistsOnceAndTranscriptRevisionUpdatesTheSameMessage() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val name = "moss-${UUID.randomUUID()}.db"
        val root = File(context.filesDir, "moss-photos-${UUID.randomUUID()}")
        val db = Room.databaseBuilder(context, ConversationDatabase::class.java, name).build()
        try {
            val repository = ConversationRepository(db, root, context.contentResolver)
            val session = repository.initialize()
            val first = repository.upsertMossTranscript(session.id, "user-turn", false, "Help with the television")
            repository.upsertMossTranscript(session.id, "assistant-turn", true, "Look at the remote.")
            val userRevised = repository.upsertMossTranscript(session.id, "user-turn", false, "Help me set up my television")
            val revised = repository.upsertMossTranscript(session.id, "assistant-turn", true, "Look at the remote.")
            assertEquals(1, first.messages.size)
            assertEquals(1, userRevised.messages.size)
            assertEquals(2, revised.messages.size)
            assertEquals(listOf(ChatKind.TEXT, ChatKind.ASSISTANT), revised.messages.map { it.kind })
            assertEquals("Help me set up my television", revised.messages.first().text)
            assertEquals("Look at the remote.", revised.messages.last().text)
            assertEquals("user-turn", revised.messages.first().turnId)
            assertEquals("assistant-turn", revised.messages.last().turnId)
            val transcriptOnly = repository.upsertMossTranscript(session.id, "another-user-turn", false, "Which button is power?")
            assertEquals(3, transcriptOnly.messages.size)
            assertEquals("Which button is power?", transcriptOnly.messages.last().text)
        } finally {
            db.close()
            context.deleteDatabase(name)
            root.delete()
        }
    }

    @Test fun voiceTranscriptIsIdempotentAndSurvivesDatabaseReopen() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val id = UUID.randomUUID().toString()
        val name = "voice-$id.db"
        val root = File(context.filesDir, "voice-photos-$id")
        var db: ConversationDatabase? = null
        try {
            db = Room.databaseBuilder(context, ConversationDatabase::class.java, name).build()
            var repository = ConversationRepository(db, root, context.contentResolver)
            val session = repository.initialize()
            val saved = repository.addVoiceTranscript(session.id, "voice-message", "Which button?")
            assertEquals(ChatKind.VOICE_TRANSCRIPT, saved.messages.single().kind)
            assertEquals(ChatSource.PHONE, saved.messages.single().source)
            repository.addVoiceTranscript(session.id, "voice-message", "Which button?")
            assertEquals(1, repository.open(session.id).messages.size)
            assertEquals("Which button?", repository.history().single().title)
            db.close()
            db = Room.databaseBuilder(context, ConversationDatabase::class.java, name).build()
            repository = ConversationRepository(db, root, context.contentResolver)
            assertEquals("Which button?", repository.open(session.id).messages.single().text)
            try {
                repository.addVoiceTranscript(session.id, "voice-message", "Different text")
                fail("An existing message ID must not be reused for different text")
            } catch (_: java.io.IOException) { }
        } finally {
            db?.close()
            context.deleteDatabase(name)
            root.delete()
        }
    }

    @Test fun combinedTimelineKeepsSequenceAndPendingTurnAcrossPageBoundary() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val id = UUID.randomUUID().toString()
        val name = "timeline-$id.db"
        val root = File(context.filesDir, "timeline-$id")
        val db = Room.databaseBuilder(context, ConversationDatabase::class.java, name).build()
        try {
            val dao = db.conversations()
            dao.insertConversation(ConversationEntity("recent", 1, 1, "Recent"))
            dao.insertConversation(ConversationEntity("saved", 2, 2, "Saved", saved = true))
            val rows = (0 until 205).map { index ->
                MessageEntity(id = "message-$index", conversationId = if (index % 2 == 0) "recent" else "saved",
                    turnId = "turn-$index", timestamp = index.toLong(), kind = ChatKind.TEXT.name,
                    source = ChatSource.PHONE.name, text = "message $index", status = ChatStatus.READY.name)
            }
            // This two-message turn straddles the 200-row backwards-read boundary.
            val split = rows.toMutableList()
            split[4] = split[4].copy(conversationId = "recent", turnId = "split")
            split[5] = split[5].copy(conversationId = "recent", turnId = "split", kind = ChatKind.ASSISTANT.name,
                source = ChatSource.ASSISTANT.name, status = ChatStatus.SENDING.name)
            dao.insertMessages(split)
            val repository = ConversationRepository(db, root, context.contentResolver, recentLimitBytes = 1_000_000)
            val firstPage = repository.recentTimeline(200)
            assertTrue(firstPage.canLoadMore)
            assertEquals(split.drop(6).map { it.id }, firstPage.messages.map { it.id })
            val timeline = repository.recentTimeline(300)
            assertFalse(timeline.displayLimited)
            assertEquals(split.map { it.id }, timeline.messages.map { it.id })
            assertEquals(ChatStatus.SENDING, timeline.messages[5].status)
        } finally {
            db.close()
            context.deleteDatabase(name)
            root.delete()
        }
    }

    @Test fun versionOneMigrationPreservesExistingHistoryAsSaved() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val name = "migration-${UUID.randomUUID()}.db"
        val path = context.getDatabasePath(name)
        val root = File(context.filesDir, "migration-photos-${UUID.randomUUID()}")
        val imageName = "00000000-0000-0000-0000-000000000001.jpg"
        root.mkdirs()
        val stored = File(root, imageName)
        stored.writeBytes(byteArrayOf(1, 2, 3))
        path.parentFile?.mkdirs()
        var db: ConversationDatabase? = null
        try {
            SQLiteDatabase.openOrCreateDatabase(path, null).use { old ->
                old.execSQL("CREATE TABLE conversations (id TEXT NOT NULL PRIMARY KEY, createdAt INTEGER NOT NULL, lastActivity INTEGER NOT NULL, title TEXT NOT NULL)")
                old.execSQL("CREATE TABLE messages (sequence INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, id TEXT NOT NULL, conversationId TEXT NOT NULL, turnId TEXT NOT NULL, timestamp INTEGER NOT NULL, kind TEXT NOT NULL, source TEXT NOT NULL, text TEXT NOT NULL, status TEXT NOT NULL, imageKey TEXT, captureId TEXT, FOREIGN KEY(conversationId) REFERENCES conversations(id) ON UPDATE NO ACTION ON DELETE CASCADE)")
                old.execSQL("CREATE INDEX index_messages_conversationId ON messages (conversationId)")
                old.execSQL("CREATE UNIQUE INDEX index_messages_id ON messages (id)")
                old.execSQL("INSERT INTO conversations VALUES ('old', 1, 1, 'Old chat')")
                old.execSQL("INSERT INTO messages (id, conversationId, turnId, timestamp, kind, source, text, status, imageKey, captureId) VALUES ('photo', 'old', 'turn', 1, 'PHOTO', 'PHONE', '', 'READY', '$imageName', 'capture')")
                old.execSQL("INSERT INTO messages (id, conversationId, turnId, timestamp, kind, source, text, status) VALUES ('message', 'old', 'turn', 1, 'TEXT', 'PHONE', 'Keep me', 'READY')")
                old.version = 1
            }
            db = Room.databaseBuilder(context, ConversationDatabase::class.java, name).addMigrations(MIGRATION_1_2).build()
            assertTrue(db.conversations().conversation("old")!!.saved)
            assertEquals("Keep me", db.conversations().messages("old").last().text)
            val migrated = ConversationRepository(db, root, context.contentResolver).initialize()
            assertTrue(migrated.saved)
            assertEquals(stored.toURI().toString(), migrated.currentPhoto!!.localUri)
            assertTrue(stored.isFile)
        } finally {
            db?.close()
            context.deleteDatabase(name)
            stored.delete()
            root.delete()
        }
    }

    @Test fun reusedPhotoSurvivesEvictionAndSavedChatsAreProtected() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val id = UUID.randomUUID().toString()
        val dbName = "rolling-$id.db"
        val root = File(context.filesDir, "rolling-$id")
        val source = File(context.cacheDir, "rolling-$id.jpg")
        var db: ConversationDatabase? = null
        try {
            val bitmap = Bitmap.createBitmap(80, 50, Bitmap.Config.ARGB_8888)
            try { bitmap.eraseColor(Color.BLUE); source.outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG, 90, it) } }
            finally { bitmap.recycle() }
            db = Room.databaseBuilder(context, ConversationDatabase::class.java, dbName).build()
            var repository = ConversationRepository(db, root, context.contentResolver)
            val first = repository.initialize()
            val capture = CapturedPhoto(id, source.toURI().toString(), Instant.now())
            val pending = repository.beginTurn(first.id, capture, "a".repeat(200), true)
            val stored = File(java.net.URI(pending.currentPhoto!!.localUri))
            repository.finishTurn(first.id, pending.messages.last().id, "ok", false)
            repository = ConversationRepository(db, root, context.contentResolver, recentLimitBytes = stored.length() + 110)
            val second = repository.beginTurn(first.id, pending.currentPhoto!!, "b".repeat(100), false)
            val trimmed = repository.finishTurn(first.id, second.messages.last().id, "ok", false)
            assertTrue(trimmed.truncated)
            assertTrue(stored.isFile)
            assertEquals(listOf(ChatKind.PHOTO, ChatKind.TEXT, ChatKind.ASSISTANT), trimmed.messages.map { it.kind })
            assertEquals("b".repeat(80), repository.history().single().title)
            assertTrue(repository.save(first.id).saved)
            db.close()
            db = Room.databaseBuilder(context, ConversationDatabase::class.java, dbName).build()
            repository = ConversationRepository(db, root, context.contentResolver, recentLimitBytes = stored.length() + 110)
            assertTrue(repository.initialize().saved)
            val recent = repository.newConversation()
            val textTurn = repository.beginTextTurn(recent.id, "c".repeat(300))
            repository.finishTurn(recent.id, textTurn.messages.last().id, "response", false)
            assertTrue(repository.open(first.id).saved)
            assertTrue(stored.isFile)
            repository.delete(first.id, recent.id)
            assertFalse(stored.exists())
        } finally {
            db?.close()
            context.deleteDatabase(dbName)
            source.delete()
            root.listFiles()?.forEach { it.delete() }
            root.delete()
        }
    }
    @Test fun conversationAndPhotoSurviveDatabaseReopenAndAreDeletedTogether() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val id = UUID.randomUUID().toString()
        val dbName = "test-conversations-$id.db"
        val root = File(context.filesDir, "test-conversation-$id")
        val source = File(context.cacheDir, "test-source-$id.jpg")
        var db: ConversationDatabase? = null
        try {
            val bitmap = Bitmap.createBitmap(80, 50, Bitmap.Config.ARGB_8888)
            try {
                bitmap.eraseColor(Color.BLUE)
                source.outputStream().use { assertTrue(bitmap.compress(Bitmap.CompressFormat.JPEG, 90, it)) }
            } finally { bitmap.recycle() }
            fun openDb() = Room.databaseBuilder(context, ConversationDatabase::class.java, dbName).build()
            db = openDb()
            var repository = ConversationRepository(db, root, context.contentResolver)
            val initial = repository.initialize()
            val capture = CapturedPhoto(UUID.randomUUID().toString(), source.toURI().toString(), Instant.now())
            val pending = repository.beginTurn(initial.id, capture, "Which button?", includePhoto = true)
            val stored = File(java.net.URI(pending.currentPhoto!!.localUri))
            assertTrue(stored.isFile)
            val response = pending.messages.last()
            repository.finishTurn(initial.id, response.id, "Simulated response", failed = false)
            val second = repository.newConversation()
            val textTurn = repository.beginTextTurn(second.id, "Another question")
            repository.finishTurn(second.id, textTurn.messages.last().id, "response", false)
            db.close()
            db = openDb()
            repository = ConversationRepository(db, root, context.contentResolver)
            assertEquals(second.id, repository.initialize().id)
            assertEquals(2, repository.history().size)
            val reopened = repository.open(initial.id)
            assertEquals(listOf(ChatKind.PHOTO, ChatKind.TEXT, ChatKind.ASSISTANT), reopened.messages.map { it.kind })
            assertEquals("Which button?", reopened.messages[1].text)
            assertEquals("Simulated response", reopened.messages[2].text)
            assertTrue(File(java.net.URI(reopened.currentPhoto!!.localUri)).isFile)
            val remaining = repository.delete(initial.id, initial.id)
            assertEquals(second.id, remaining.id)
            assertFalse(stored.exists())
            db.close()
            db = openDb()
            repository = ConversationRepository(db, root, context.contentResolver)
            assertEquals(listOf(second.id), repository.history().map { it.id })
        } finally {
            db?.close()
            context.deleteDatabase(dbName)
            source.delete()
            root.listFiles()?.forEach { it.delete() }
            root.delete()
        }
    }
}
