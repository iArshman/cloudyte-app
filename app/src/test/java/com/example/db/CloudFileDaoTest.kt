package com.example.db

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class CloudFileDaoTest {

    private lateinit var db: AppDatabase
    private lateinit var dao: CloudFileDao

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        dao = db.cloudFileDao()
    }

    @After
    fun tearDown() {
        db.close()
    }

    private fun sampleRow(
        id: String,
        name: String,
        accountId: String = "acc1",
        folderId: String = "root",
        updatedAt: Long? = 1000L
    ): LocalCloudFile {
        return LocalCloudFile(
            id = id,
            name = name,
            size = "1 KB",
            rawSize = 1024L,
            type = "file",
            mimeType = "text/plain",
            time = updatedAt ?: 0L,
            updatedAt = updatedAt,
            thumbUrl = "/api/thumb/$accountId/$id",
            isFolder = false,
            accountId = accountId,
            folderId = folderId
        )
    }

    @Test
    fun replaceFolder_removesStaleRowsAndKeepsOthers() = runTest {
        // Seed initial rows in (acc1, root), plus rows in another folder and another account
        val staleRow = sampleRow(id = "stale_1", name = "OldStale.txt", accountId = "acc1", folderId = "root")
        val keptRow = sampleRow(id = "keep_1", name = "KeptOldName.txt", accountId = "acc1", folderId = "root", updatedAt = 100L)
        val otherFolderRow = sampleRow(id = "other_folder_1", name = "SubfolderFile.txt", accountId = "acc1", folderId = "sub_1")
        val otherAccountRow = sampleRow(id = "other_acc_1", name = "OtherAccountFile.txt", accountId = "acc2", folderId = "root")

        dao.insertFiles(listOf(staleRow, keptRow, otherFolderRow, otherAccountRow))

        // Replace (acc1, root) with updated keptRow + brand new row (staleRow is omitted)
        val updatedKeptRow = keptRow.copy(name = "KeptUpdatedName.txt", updatedAt = 200L)
        val newRow = sampleRow(id = "new_1", name = "FreshFile.txt", accountId = "acc1", folderId = "root", updatedAt = 300L)

        dao.replaceFolder("acc1", "root", listOf(updatedKeptRow, newRow))

        val acc1RootFiles = dao.getFilesForFolder("acc1", "root")
        val acc1RootIds = acc1RootFiles.map { it.id }.toSet()

        // Stale row removed, kept row updated, new row present
        assertEquals(setOf("keep_1", "new_1"), acc1RootIds)
        assertEquals("KeptUpdatedName.txt", acc1RootFiles.first { it.id == "keep_1" }.name)
        assertEquals(200L, acc1RootFiles.first { it.id == "keep_1" }.updatedAt)

        // Rows in other folders and other accounts remain untouched
        val subFolderFiles = dao.getFilesForFolder("acc1", "sub_1")
        assertEquals(1, subFolderFiles.size)
        assertEquals("other_folder_1", subFolderFiles.first().id)

        val acc2RootFiles = dao.getFilesForFolder("acc2", "root")
        assertEquals(1, acc2RootFiles.size)
        assertEquals("other_acc_1", acc2RootFiles.first().id)
    }

    @Test
    fun replaceFolder_emptyListDeletesOnlyTargetFolderRows() = runTest {
        val row1 = sampleRow(id = "f1", name = "A.txt", accountId = "acc1", folderId = "root")
        val otherRow = sampleRow(id = "f2", name = "B.txt", accountId = "acc1", folderId = "photos")
        dao.insertFiles(listOf(row1, otherRow))

        dao.replaceFolder("acc1", "root", emptyList())

        assertTrue(dao.getFilesForFolder("acc1", "root").isEmpty())
        assertEquals(1, dao.getFilesForFolder("acc1", "photos").size)
    }
}
