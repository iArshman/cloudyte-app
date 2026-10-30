package com.cloudyte.app.data.local.entities

import com.cloudyte.app.db.LocalAccount as LegacyAccountEntity
import com.cloudyte.app.db.LocalCloudFile as LegacyCloudFileEntity
import com.cloudyte.app.db.LocalExclusion as LegacyExclusionEntity
import com.cloudyte.app.db.LocalFolderSyncMeta as LegacyFolderSyncMetaEntity
import com.cloudyte.app.db.LocalStorageSummary as LegacyStorageSummaryEntity
import com.cloudyte.app.db.LocalTransfer as LegacyTransferEntity
import com.cloudyte.app.db.LocalUploadRecord as LegacyUploadRecordEntity

typealias AccountEntity = LegacyAccountEntity
typealias CloudFileEntity = LegacyCloudFileEntity
typealias ExclusionEntity = LegacyExclusionEntity
typealias FolderSyncMetaEntity = LegacyFolderSyncMetaEntity
typealias StorageSummaryEntity = LegacyStorageSummaryEntity
typealias TransferEntity = LegacyTransferEntity
typealias UploadRecordEntity = LegacyUploadRecordEntity
