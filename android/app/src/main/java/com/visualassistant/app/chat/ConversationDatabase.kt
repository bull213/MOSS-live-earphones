package com.visualassistant.app.chat

import android.content.Context
import androidx.room.Dao
import androidx.room.ColumnInfo
import androidx.room.Database
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.RoomDatabase
import androidx.room.Room
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Entity(tableName = "conversations")
data class ConversationEntity(
    @PrimaryKey val id: String,
    val createdAt: Long,
    val lastActivity: Long,
    val title: String,
    @ColumnInfo(defaultValue = "1") val saved: Boolean = false,
    @ColumnInfo(defaultValue = "0") val truncated: Boolean = false,
)

@Entity(
    tableName = "messages",
    foreignKeys = [ForeignKey(entity = ConversationEntity::class, parentColumns = ["id"],
        childColumns = ["conversationId"], onDelete = ForeignKey.CASCADE)],
    indices = [Index("conversationId"), Index(value = ["id"], unique = true)],
)
data class MessageEntity(
    @PrimaryKey(autoGenerate = true) val sequence: Long = 0,
    val id: String,
    val conversationId: String,
    val turnId: String,
    val timestamp: Long,
    val kind: String,
    val source: String,
    val text: String,
    val status: String,
    val imageKey: String? = null,
    val captureId: String? = null,
    val notebookTitle: String? = null,
)

data class ConversationSummaryRow(
    val id: String,
    val title: String,
    val lastActivity: Long,
    val preview: String?,
    val saved: Boolean,
)

@Dao
interface ConversationDao {
    @Query("SELECT * FROM conversations ORDER BY lastActivity DESC, createdAt DESC, rowid DESC LIMIT 1")
    suspend fun latest(): ConversationEntity?

    @Query("SELECT * FROM conversations WHERE saved = 0 ORDER BY lastActivity DESC, createdAt DESC, rowid DESC LIMIT 1")
    suspend fun latestRecent(): ConversationEntity?

    @Query("SELECT MAX(lastActivity) FROM conversations")
    suspend fun maxActivity(): Long?

    @Query("SELECT * FROM conversations WHERE id = :id")
    suspend fun conversation(id: String): ConversationEntity?

    @Query("SELECT c.id, c.title, c.lastActivity, c.saved, (SELECT m.text FROM messages m WHERE m.conversationId = c.id AND m.text != '' ORDER BY m.sequence DESC LIMIT 1) AS preview FROM conversations c WHERE EXISTS (SELECT 1 FROM messages m WHERE m.conversationId = c.id) ORDER BY c.lastActivity DESC, c.createdAt DESC, c.rowid DESC")
    suspend fun history(): List<ConversationSummaryRow>

    @Query("SELECT * FROM messages WHERE conversationId = :id ORDER BY sequence ASC")
    suspend fun messages(id: String): List<MessageEntity>

    @Query("SELECT * FROM messages WHERE id = :id LIMIT 1")
    suspend fun message(id: String): MessageEntity?

    @Query("SELECT * FROM messages WHERE conversationId = :conversationId AND turnId = :turnId ORDER BY sequence ASC")
    suspend fun turnMessages(conversationId: String, turnId: String): List<MessageEntity>

    @Query("SELECT imageKey FROM messages WHERE imageKey IS NOT NULL")
    suspend fun referencedImages(): List<String>

    @Query("SELECT m.* FROM messages m JOIN conversations c ON c.id = m.conversationId WHERE c.saved = 0 ORDER BY m.sequence ASC")
    suspend fun recentMessages(): List<MessageEntity>

    @Query("SELECT * FROM messages ORDER BY sequence ASC")
    suspend fun allMessages(): List<MessageEntity>

    @Query("SELECT * FROM messages WHERE sequence < :before ORDER BY sequence DESC LIMIT :limit")
    suspend fun messagesBefore(before: Long, limit: Int): List<MessageEntity>

    @Query("UPDATE conversations SET saved = 1 WHERE id = :id")
    suspend fun saveConversation(id: String)

    @Query("UPDATE conversations SET truncated = 1 WHERE id = :id")
    suspend fun markTruncated(id: String)

    @Query("UPDATE conversations SET title = :title WHERE id = :id")
    suspend fun updateTitle(id: String, title: String)

    @Query("DELETE FROM messages WHERE conversationId = :conversationId AND turnId = :turnId")
    suspend fun deleteTurn(conversationId: String, turnId: String)

    @Query("DELETE FROM messages WHERE conversationId = :conversationId AND id = :messageId AND kind = 'VOICE_TRANSCRIPT'")
    suspend fun deleteVoiceMessage(conversationId: String, messageId: String)

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertConversation(conversation: ConversationEntity)

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertMessages(messages: List<MessageEntity>)

    @Query("UPDATE conversations SET lastActivity = :time, title = :title WHERE id = :id")
    suspend fun updateConversation(id: String, time: Long, title: String)

    @Query("UPDATE messages SET status = :status, text = :text, timestamp = :time, notebookTitle = :notebookTitle WHERE id = :id")
    suspend fun updateMessage(id: String, status: String, text: String, time: Long, notebookTitle: String? = null)

    @Query("UPDATE messages SET status = :status, text = :text, timestamp = :time WHERE id = :id")
    suspend fun updateMessageText(id: String, status: String, text: String, time: Long)

    @Query("UPDATE messages SET status = 'FAILED' WHERE status = 'SENDING'")
    suspend fun failInterruptedMessages()

    @Query("DELETE FROM conversations WHERE id = :id")
    suspend fun deleteConversation(id: String)

    @Query("SELECT m.imageKey FROM messages m JOIN conversations c ON c.id = m.conversationId WHERE c.saved = 0 AND m.imageKey IS NOT NULL")
    suspend fun recentImages(): List<String>

    @Query("DELETE FROM messages WHERE conversationId IN (SELECT id FROM conversations WHERE saved = 0)")
    suspend fun deleteRecentMessages()

    @Query("DELETE FROM conversations WHERE saved = 0")
    suspend fun deleteRecentConversations()
}

@Database(entities = [ConversationEntity::class, MessageEntity::class], version = 3, exportSchema = false)
abstract class ConversationDatabase : RoomDatabase() {
    abstract fun conversations(): ConversationDao
}

internal object ConversationDatabaseProvider {
    @Volatile private var instance: ConversationDatabase? = null

    fun get(context: Context): ConversationDatabase = instance ?: synchronized(this) {
        instance ?: Room.databaseBuilder(context.applicationContext, ConversationDatabase::class.java, "conversations.db")
            .addMigrations(MIGRATION_1_2, MIGRATION_2_3)
            .build().also { instance = it }
    }
}

internal val MIGRATION_1_2 = object : Migration(1, 2) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE conversations ADD COLUMN saved INTEGER NOT NULL DEFAULT 1")
        db.execSQL("ALTER TABLE conversations ADD COLUMN truncated INTEGER NOT NULL DEFAULT 0")
    }
}

internal val MIGRATION_2_3 = object : Migration(2, 3) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE messages ADD COLUMN notebookTitle TEXT DEFAULT NULL")
    }
}
