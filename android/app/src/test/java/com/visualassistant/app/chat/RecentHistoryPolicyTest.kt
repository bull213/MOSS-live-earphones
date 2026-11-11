package com.visualassistant.app.chat

import org.junit.Assert.assertEquals
import org.junit.Test

class RecentHistoryPolicyTest {
    private fun row(sequence: Long, conversation: String, turn: String, text: String = "", image: String? = null, status: String = "READY") =
        MessageEntity(sequence, "id-$sequence", conversation, turn, sequence, "TEXT", "PHONE", text, status, image)

    @Test fun removesOldestCompleteTurnsAcrossConversationsAndCountsSharedPhotosOnce() {
        val policy = RecentHistoryPolicy(13) { 10 }
        val rows = listOf(row(1, "a", "one", "aa", "photo"), row(2, "a", "two", "bb", "photo"), row(3, "b", "three", "cc"))
        assertEquals(listOf("a" to "one", "a" to "two"), policy.turnsToRemove(rows))
    }

    @Test fun protectsPendingAndNewestTurnsEvenWhenBudgetIsExceeded() {
        val policy = RecentHistoryPolicy(1) { 0 }
        val rows = listOf(row(1, "a", "pending", "old", status = "SENDING"), row(2, "b", "new", "new"))
        assertEquals(emptyList<Pair<String, String>>(), policy.turnsToRemove(rows))
    }

    @Test fun contextIncludesNewestWholeTurnsWithinBudget() {
        val policy = RecentHistoryPolicy(5) { 0 }
        val rows = listOf(row(1, "a", "one", "old"), row(2, "a", "two", "n"), row(3, "a", "two", "ew"))
        assertEquals(rows.drop(1), policy.boundedContext(rows))
    }

    @Test fun contextCountsUtf8BytesAndSharedPhotoOnlyOnce() {
        val policy = RecentHistoryPolicy(8) { 4 }
        val rows = listOf(row(1, "a", "one", "é", "photo"), row(2, "a", "two", "é", "photo"))
        assertEquals(rows, policy.boundedContext(rows))
        assertEquals(emptyList<Pair<String, String>>(), policy.turnsToRemove(rows))
    }
}
