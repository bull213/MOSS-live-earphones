package com.visualassistant.app.camera

import java.io.File
import java.io.IOException
import java.util.UUID
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class CaptureStoreTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun freshSessionRemovesOnlyOwnedAbandonedCaptures() {
        val directory = temporary.newFolder("captures")
        val abandoned = File(directory, "capture-abandoned.jpg").apply { writeText("old") }
        val unrelated = File(directory, "notes.txt").apply { writeText("keep") }
        val outside = File(temporary.root, "capture-outside.jpg").apply { writeText("keep") }
        CaptureStore(directory).prepare()
        assertFalse(abandoned.exists())
        assertTrue(unrelated.exists())
        assertTrue(outside.exists())
    }

    @Test fun capturesHaveDistinctIdsAndCanBeRemovedIdempotently() {
        val store = CaptureStore(temporary.newFolder("captures"))
        val first = store.createFile()
        val second = store.createFile()
        val photo = store.photo(first)
        UUID.fromString(photo.id)
        assertNotEquals(photo.id, store.photo(second).id)
        assertEquals(first.toURI().toString(), photo.localUri)
        store.discard(photo)
        store.discard(photo)
        assertFalse(first.exists())
        assertTrue(second.exists())
    }

    @Test fun rejectsDeletionOutsideCaptureDirectory() {
        val store = CaptureStore(temporary.newFolder("captures"))
        val outside = File(temporary.root, "capture-outside.jpg").apply { writeText("keep") }
        assertThrows(IllegalArgumentException::class.java) { store.discard(outside) }
        assertTrue(outside.exists())
    }

    @Test fun unreadableDirectoryFailsWithoutDeletingOtherFiles() {
        val file = temporary.newFile("not-a-directory")
        assertThrows(IOException::class.java) { CaptureStore(file).prepare() }
        assertTrue(file.exists())
    }
}
