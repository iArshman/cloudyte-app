package com.cloudyte.app.data.local

import com.cloudyte.app.db.AccountDao
import com.cloudyte.app.db.AppDatabase
import com.cloudyte.app.db.CloudFileDao
import com.cloudyte.app.db.ExclusionDao
import com.cloudyte.app.db.FolderSyncMetaDao
import com.cloudyte.app.db.LocalAccount
import com.cloudyte.app.db.LocalCloudFile
import com.cloudyte.app.db.LocalExclusion
import com.cloudyte.app.db.LocalFolderSyncMeta
import com.cloudyte.app.db.LocalStorageSummary
import com.cloudyte.app.db.LocalTransfer
import com.cloudyte.app.db.LocalUploadRecord
import com.cloudyte.app.db.StorageSummaryDao
import com.cloudyte.app.db.TransferDao
import com.cloudyte.app.db.UploadRecordDao

typealias CloudyteDatabase = AppDatabase
typealias AccountLocalDao = AccountDao
typealias CloudFileLocalDao = CloudFileDao
typealias ExclusionLocalDao = ExclusionDao
typealias FolderSyncMetaLocalDao = FolderSyncMetaDao
typealias StorageSummaryLocalDao = StorageSummaryDao
typealias TransferLocalDao = TransferDao
typealias UploadRecordLocalDao = UploadRecordDao
typealias AccountEntity = LocalAccount
typealias CloudFileEntity = LocalCloudFile
typealias ExclusionEntity = LocalExclusion
typealias FolderSyncMetaEntity = LocalFolderSyncMeta
typealias StorageSummaryEntity = LocalStorageSummary
typealias TransferEntity = LocalTransfer
typealias UploadRecordEntity = LocalUploadRecord
