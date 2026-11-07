package com.wobblebot.papotons.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class CorruptedDatabaseCopiesTest {
    @get:Rule
    val folder = TemporaryFolder()

    private val copies by lazy { CorruptedDatabaseCopies(folder.root) }

    private fun copy(name: String) = File(copies.directory, name).mkdirs()

    private fun kept() = copies.directory.list().orEmpty().sorted()

    @Test
    fun `the edit marker is never taken for a copy`() {
        copies.markEdited()
        assertFalse(copies.hasCopies())

        copy("000001-a")
        copies.markEdited()
        for (name in listOf("000002-b", "000003-c", "000004-d")) copy(name)
        copies.prune()

        assertTrue(copies.hasCopies())
        assertEquals(listOf("000001-a", "000002-b", "000004-d"), kept())
    }

    @Test
    fun `a copy in progress is neither counted nor pruned, and names follow the highest number`() {
        copy("${PENDING_COPY_PREFIX}y")
        assertFalse(copies.hasCopies())
        copy("000009-z")

        assertEquals("000010-a", copies.nextName("a"))
        for (name in listOf("000010-a", "000011-b", "000012-c")) copy(name)
        copies.prune()
        assertTrue("${PENDING_COPY_PREFIX}y" in kept())
    }

    @Test
    fun `the first copy after an edit survives four corruptions`() {
        copy("000001-original")
        // Grilles refaites après la première perte.
        copies.markEdited()
        copy("000002-redone")
        for (name in listOf("000003-c", "000004-d", "000005-e", "000006-f")) {
            copy(name)
            copies.prune()
        }

        assertEquals(listOf("000001-original", "000002-redone", "000006-f"), kept())
    }

    @Test
    fun `without any edit the first and the newest copies are kept`() {
        for (name in listOf("000001-a", "000002-b", "000003-c", "000004-d")) copy(name)

        copies.prune()

        assertEquals(listOf("000001-a", "000003-c", "000004-d"), kept())
    }

    @Test
    fun `an edit with no copy writes no marker`() {
        copies.markEdited()

        assertFalse(File(folder.root, "$CORRUPTED_DATABASES_DIRECTORY.edited").exists())
    }

    @Test
    fun `an unreadable edit marker prunes nothing`() {
        File(folder.root, "$CORRUPTED_DATABASES_DIRECTORY.edited").writeText("illisible")
        val names = listOf("000001-a", "000002-b", "000003-c", "000004-d", "000005-e")
        for (name in names) copy(name)

        copies.prune()

        assertEquals(names, kept())
    }
}
