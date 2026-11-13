package com.visualassistant.app.chat

import androidx.sqlite.db.SupportSQLiteDatabase
import org.junit.Assert.assertEquals
import org.junit.Test
import java.lang.reflect.Proxy

class ConversationMigrationTest {
    @Test
    fun migration2to3ExecutesAlterTable() {
        var executedSql: String? = null
        val db = Proxy.newProxyInstance(
            SupportSQLiteDatabase::class.java.classLoader,
            arrayOf(SupportSQLiteDatabase::class.java),
        ) { _, method, args ->
            if (method.name == "execSQL") {
                executedSql = args?.get(0) as? String
            }
            null
        } as SupportSQLiteDatabase

        MIGRATION_2_3.migrate(db)

        assertEquals("ALTER TABLE messages ADD COLUMN notebookTitle TEXT DEFAULT NULL", executedSql)
        assertEquals(2, MIGRATION_2_3.startVersion)
        assertEquals(3, MIGRATION_2_3.endVersion)
    }

    @Test
    fun migration1to2ExecutesExpectedAlterTables() {
        val statements = mutableListOf<String>()
        val db = Proxy.newProxyInstance(
            SupportSQLiteDatabase::class.java.classLoader,
            arrayOf(SupportSQLiteDatabase::class.java),
        ) { _, method, args ->
            if (method.name == "execSQL") {
                (args?.get(0) as? String)?.let { statements.add(it) }
            }
            null
        } as SupportSQLiteDatabase

        MIGRATION_1_2.migrate(db)

        assertEquals(2, statements.size)
        assertEquals("ALTER TABLE conversations ADD COLUMN saved INTEGER NOT NULL DEFAULT 1", statements[0])
        assertEquals("ALTER TABLE conversations ADD COLUMN truncated INTEGER NOT NULL DEFAULT 0", statements[1])
        assertEquals(1, MIGRATION_1_2.startVersion)
        assertEquals(2, MIGRATION_1_2.endVersion)
    }
}
