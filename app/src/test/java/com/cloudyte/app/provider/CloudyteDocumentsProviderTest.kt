package com.cloudyte.app.provider

import android.content.Context
import android.content.pm.ProviderInfo
import android.os.ParcelFileDescriptor
import android.provider.DocumentsContract.Document
import android.provider.DocumentsContract.Root
import androidx.test.core.app.ApplicationProvider
import androidx.work.testing.WorkManagerTestInitHelper
import com.cloudyte.app.api.ApiClient
import com.cloudyte.app.api.OfflineFileManager
import com.cloudyte.app.db.AppDatabase
import com.cloudyte.app.db.LocalAccount
import com.cloudyte.app.db.LocalCloudFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class CloudyteDocumentsProviderTest {

    private lateinit var context: Context
    private lateinit var provider: CloudyteDocumentsProvider

    @Before
    fun setUp() {
        runBlocking(Dispatchers.IO) {
            context = ApplicationProvider.getApplicationContext()
            WorkManagerTestInitHelper.initializeTestWorkManager(context)
            ApiClient.saveToken(null)
            val db = AppDatabase.getDatabase(context)
            db.clearAllTables()
            OfflineFileManager.clearAllOfflineCache(context)

            val info = ProviderInfo().apply {
                authority = "${context.packageName}.documents"
                exported = true
                grantUriPermissions = true
                readPermission = android.Manifest.permission.MANAGE_DOCUMENTS
                writePermission = android.Manifest.permission.MANAGE_DOCUMENTS
            }
            provider = Robolectric.buildContentProvider(CloudyteDocumentsProvider::class.java)
                .create(info)
                .get()
        }
    }

    @After
    fun tearDown() {
        runBlocking(Dispatchers.IO) {
            ApiClient.saveToken(null)
            AppDatabase.getDatabase(context).clearAllTables()
            OfflineFileManager.clearAllOfflineCache(context)
        }
    }

    @Test
    fun queryRoots_and_browseFolderHierarchy_and_readOfflineDocument() {
        runBlocking(Dispatchers.IO) {
        val db = AppDatabase.getDatabase(context)
        val accId = "acc:gdrive_1"
        val otherAccId = "acc_onedrive_2"

        db.accountDao().insertAccounts(
            listOf(
                LocalAccount(
                    id = accId,
                    name = "Work Drive",
                    driveName = "Work Drive",
                    email = "work@example.com",
                    accountType = "gdrive",
                    usageBytes = 2_000L,
                    totalBytes = 10_000L,
                    percentage = 20f,
                    fileCount = 2,
                    isDefault = true,
                    channelId = null
                ),
                LocalAccount(
                    id = otherAccId,
                    name = "Personal OneDrive",
                    driveName = "Personal OneDrive",
                    email = "personal@example.com",
                    accountType = "onedrive",
                    usageBytes = 500L,
                    totalBytes = 5_000L,
                    percentage = 10f,
                    fileCount = 1,
                    isDefault = false,
                    channelId = null
                )
            )
        )

        db.cloudFileDao().insertFiles(
            listOf(
                LocalCloudFile(
                    id = "folder_invoices",
                    name = "Invoices",
                    type = "folder",
                    size = "0 B",
                    rawSize = 0L,
                    isFolder = true,
                    accountId = accId,
                    folderId = "root"
                ),
                LocalCloudFile(
                    id = "file_q1_pdf",
                    name = "Q1_Report.pdf",
                    type = "pdf",
                    size = "4 KB",
                    rawSize = 4096L,
                    isFolder = false,
                    accountId = accId,
                    folderId = "folder_invoices"
                )
            )
        )

        // 1. Verify queryRoots exposes both connected drives
        provider.queryRoots(null).use { rootsCursor ->
            assertEquals(2, rootsCursor.count)
            assertTrue(rootsCursor.moveToFirst())
            val rootId = rootsCursor.getString(rootsCursor.getColumnIndexOrThrow(Root.COLUMN_ROOT_ID))
            val rootDocId = rootsCursor.getString(rootsCursor.getColumnIndexOrThrow(Root.COLUMN_DOCUMENT_ID))
            val title = rootsCursor.getString(rootsCursor.getColumnIndexOrThrow(Root.COLUMN_TITLE))
            val available = rootsCursor.getLong(rootsCursor.getColumnIndexOrThrow(Root.COLUMN_AVAILABLE_BYTES))

            assertEquals(CloudyteDocumentsProvider.buildRootId(accId), rootId)
            assertEquals(CloudyteDocumentsProvider.buildDocumentId(accId, "root"), rootDocId)
            assertEquals("Cloudyte (Work Drive)", title)
            assertEquals(8_000L, available)
        }

        // 2. Verify queryChildDocuments on root returns "Invoices" folder
        val rootDocId = CloudyteDocumentsProvider.buildDocumentId(accId, "root")
        val invoicesDocId = CloudyteDocumentsProvider.buildDocumentId(accId, "folder_invoices")
        val pdfDocId = CloudyteDocumentsProvider.buildDocumentId(accId, "file_q1_pdf")

        provider.queryChildDocuments(rootDocId, null, null as String?).use { childrenCursor ->
            assertEquals(1, childrenCursor.count)
            assertTrue(childrenCursor.moveToFirst())
            assertEquals(
                invoicesDocId,
                childrenCursor.getString(childrenCursor.getColumnIndexOrThrow(Document.COLUMN_DOCUMENT_ID))
            )
            assertEquals(
                Document.MIME_TYPE_DIR,
                childrenCursor.getString(childrenCursor.getColumnIndexOrThrow(Document.COLUMN_MIME_TYPE))
            )
        }

        // 3. Verify queryChildDocuments on "Invoices" returns "Q1_Report.pdf"
        provider.queryChildDocuments(invoicesDocId, null, null as String?).use { subCursor ->
            assertEquals(1, subCursor.count)
            assertTrue(subCursor.moveToFirst())
            assertEquals(
                pdfDocId,
                subCursor.getString(subCursor.getColumnIndexOrThrow(Document.COLUMN_DOCUMENT_ID))
            )
            assertEquals(
                "Q1_Report.pdf",
                subCursor.getString(subCursor.getColumnIndexOrThrow(Document.COLUMN_DISPLAY_NAME))
            )
        }

        // 4. Verify isChildDocument hierarchy & cross-account isolation
        assertTrue(provider.isChildDocument(rootDocId, invoicesDocId))
        assertTrue(provider.isChildDocument(rootDocId, pdfDocId))
        assertTrue(provider.isChildDocument(invoicesDocId, pdfDocId))
        assertFalse(
            provider.isChildDocument(
                CloudyteDocumentsProvider.buildDocumentId(otherAccId, "root"),
                pdfDocId
            )
        )

        // 5. Verify querySearchDocuments finds "Q1_Report.pdf"
        provider.querySearchDocuments(CloudyteDocumentsProvider.buildRootId(accId), "Q1_Report", null).use { searchCursor ->
            assertEquals(1, searchCursor.count)
            assertTrue(searchCursor.moveToFirst())
            assertEquals(
                pdfDocId,
                searchCursor.getString(searchCursor.getColumnIndexOrThrow(Document.COLUMN_DOCUMENT_ID))
            )
        }

        // 6. Verify openDocument("r") serves offline vault content
        val offlineFile = OfflineFileManager.getLocalFile(context, accId, "file_q1_pdf", "Q1_Report.pdf")
        offlineFile.writeText("PDF-CONTENT-12345")
        provider.openDocument(pdfDocId, "r", null).use { pfd ->
            ParcelFileDescriptor.AutoCloseInputStream(pfd).use { input ->
                val text = input.bufferedReader().readText()
                assertEquals("PDF-CONTENT-12345", text)
            }
        }

        // 7. Verify createDocument creates a new file entry in the folder
        val createdDocId = provider.createDocument(invoicesDocId, "text/plain", "notes.txt")
        assertNotNull(createdDocId)
        provider.queryDocument(createdDocId, null).use { docCursor ->
            assertEquals(1, docCursor.count)
            assertTrue(docCursor.moveToFirst())
            assertEquals(
                "notes.txt",
                docCursor.getString(docCursor.getColumnIndexOrThrow(Document.COLUMN_DISPLAY_NAME))
            )
        }
        }
    }
}
