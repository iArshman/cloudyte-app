# Cloudyte Production Implementation Blueprint

**Role:** Principal Android Architect specification  
**Scope:** Cloudyte Android client, Cloudyte server contract, background reliability, multi-provider architecture, transfer engine, caching, lifecycle behavior, and production readiness  
**Implementation mode:** This document is an autonomous technical specification. It intentionally provides contracts, schemas, file paths, flows, invariants, and acceptance criteria rather than implementation code.

---

## 1. Executive decision

Cloudyte must remain a multi-provider cloud client. The target architecture is not a Telegram-specific clone. Every provider must be isolated behind a capability-based contract, while the Android application owns a provider-neutral local index, transfer queue, cache policy, lifecycle scheduler, and UI state model.

The production target is:

1. **Local-first browsing:** Room is the immediate source for visible file listings. Network refreshes reconcile the index; they never block initial rendering.
2. **Durable operations:** Every upload, download, rename, delete, folder creation, move, copy, and cache request has a durable identity and survives process death.
3. **One transfer engine:** UI, backup, share, offline-save, and retry actions enqueue commands into the same transfer queue. No feature may upload or download directly from a Composable.
4. **Provider-neutral contracts:** Telegram, Google Drive, OneDrive, and Cloudyte implement the same capabilities, but each may expose different resumability, thumbnails, limits, and authentication behavior.
5. **Explicit network policy:** Metered/unmetered, validated/unvalidated, roaming, battery, charging, and user preferences are evaluated before scheduling work.
6. **Bounded background work:** WorkManager schedules durable work; a foreground data-sync execution path handles long transfers; workers are idempotent and resumable.
7. **Tiered caching:** Metadata, thumbnails, previews, and complete offline files have separate stores, quotas, eviction rules, encryption rules, and invalidation semantics.
8. **Observable correctness:** Every state transition is auditable locally and diagnosable through structured logs, transfer diagnostics, retry reasons, and server correlation IDs.

Cloudyte is currently architecturally promising and has much of the foundation: Room-first browsing, WorkManager, resumable server uploads, provider adapter types, Material loading states, offline mutation records, and Coil caching. The remaining work is to make those pieces one coherent production system rather than a collection of compatible paths.

---

## 2. Current baseline and invariants

### 2.1 Current Android baseline

The blueprint assumes these existing paths and preserves them unless a migration explicitly replaces them:

```text
app/src/main/java/com/cloudyte/app/AppContainer.kt
app/src/main/java/com/cloudyte/app/CloudyteApplication.kt
app/src/main/java/com/cloudyte/app/MainActivity.kt
app/src/main/java/com/cloudyte/app/api/ApiClient.kt
app/src/main/java/com/cloudyte/app/api/CloudyteApi.kt
app/src/main/java/com/cloudyte/app/api/ApiModels.kt
app/src/main/java/com/cloudyte/app/api/OfflineFileManager.kt
app/src/main/java/com/cloudyte/app/data/CloudFileRepository.kt
app/src/main/java/com/cloudyte/app/data/TransferRepository.kt
app/src/main/java/com/cloudyte/app/data/UserPreferences.kt
app/src/main/java/com/cloudyte/app/data/providers/CloudProvider.kt
app/src/main/java/com/cloudyte/app/data/providers/ProviderRegistry.kt
app/src/main/java/com/cloudyte/app/db/AppDatabase.kt
app/src/main/java/com/cloudyte/app/db/CloudFileDao.kt
app/src/main/java/com/cloudyte/app/db/TransferDao.kt
app/src/main/java/com/cloudyte/app/db/LocalCloudFile.kt
app/src/main/java/com/cloudyte/app/db/LocalTransfer.kt
app/src/main/java/com/cloudyte/app/db/LocalFileMutation.kt
app/src/main/java/com/cloudyte/app/db/FileMutationDao.kt
app/src/main/java/com/cloudyte/app/core/transfer/TransferCoordinator.kt
app/src/main/java/com/cloudyte/app/core/transfer/TransferEngine.kt
app/src/main/java/com/cloudyte/app/core/transfer/TransferWorker.kt
app/src/main/java/com/cloudyte/app/core/transfer/TransferModels.kt
app/src/main/java/com/cloudyte/app/core/transfer/ResumableTransfer.kt
app/src/main/java/com/cloudyte/app/core/transfer/ChunkStore.kt
app/src/main/java/com/cloudyte/app/core/transfer/RetryPolicy.kt
app/src/main/java/com/cloudyte/app/sync/BackupWorker.kt
app/src/main/java/com/cloudyte/app/sync/FileMutationWorker.kt
app/src/main/java/com/cloudyte/app/sync/AutoSyncManager.kt
app/src/main/java/com/cloudyte/app/ui/components/FileThumb.kt
app/src/main/java/com/cloudyte/app/ui/components/AppLoadingIndicator.kt
app/src/main/java/com/cloudyte/app/ui/screens/FilesScreen.kt
app/src/main/java/com/cloudyte/app/ui/screens/HomeScreen.kt
app/src/main/java/com/cloudyte/app/ui/screens/TransfersScreen.kt
```

### 2.2 Current database baseline

The current Room database is version 9. Migration `8 -> 9` introduces `file_mutations`. Do not delete or rewrite historical migrations. Every future schema change must add a numbered migration and a schema JSON export.

The next planned version is **10**. All schema additions in this blueprint are grouped into migrations `9 -> 10`, `10 -> 11`, and later only when the change is independently deployable.

### 2.3 Non-negotiable invariants

- A remote file is identified by `(providerType, accountId, remoteId)`, never by remote ID alone.
- A local file row is not deleted merely because one refresh omitted it; absence must be interpreted with folder sync metadata and provider completeness.
- A transfer command is never considered complete solely because a worker returned success; the durable transfer row must be terminal first.
- A retry must reuse the same operation ID and idempotency key.
- A pause request is cooperative and durable; the worker must check it between chunks and before starting a new provider request.
- A cancellation request is terminal only after the worker has stopped or the provider session has been safely abandoned.
- UI state must be derived from Room/StateFlow and explicit operation state, not from transient navigation callbacks.
- Provider-specific failure details must be mapped to a provider-neutral error taxonomy while preserving the raw diagnostic code.
- Cache entries must never outlive the account or authentication scope they belong to.
- No worker may assume the process remains alive after scheduling another worker.

---

## 3. Target module and package layout

Create or converge on the following structure. Existing legacy files may remain temporarily as adapters, but new feature code must use the target contracts.

```text
com.cloudyte.app/
├── app/
│   ├── CloudyteApplication.kt
│   ├── MainActivity.kt
│   └── AppContainer.kt
├── core/
│   ├── common/
│   │   ├── AppError.kt
│   │   ├── Result.kt
│   │   ├── Clock.kt
│   │   ├── IdGenerator.kt
│   │   └── DispatcherProvider.kt
│   ├── network/
│   │   ├── ApiClient.kt
│   │   ├── NetworkMonitor.kt
│   │   ├── NetworkPolicy.kt
│   │   ├── AuthInterceptor.kt
│   │   └── RequestCorrelationInterceptor.kt
│   ├── security/
│   │   ├── SecureTokenStore.kt
│   │   ├── AccountKeyStore.kt
│   │   └── AppLockManager.kt
│   ├── cache/
│   │   ├── MetadataCachePolicy.kt
│   │   ├── ThumbnailStore.kt
│   │   ├── PreviewStore.kt
│   │   ├── OfflineFileStore.kt
│   │   ├── CacheQuotaManager.kt
│   │   └── CacheCleanupWorker.kt
│   ├── sync/
│   │   ├── SyncCoordinator.kt
│   │   ├── SyncScheduler.kt
│   │   ├── FolderSyncWorker.kt
│   │   └── ReconciliationPolicy.kt
│   └── transfer/
│       ├── TransferCoordinator.kt
│       ├── TransferEngine.kt
│       ├── TransferQueueDrainer.kt
│       ├── TransferScheduler.kt
│       ├── TransferWorker.kt
│       ├── TransferForegroundServicePolicy.kt
│       ├── TransferCommand.kt
│       ├── TransferState.kt
│       ├── TransferProgress.kt
│       ├── RetryPolicy.kt
│       ├── ResumeCheckpoint.kt
│       ├── ChunkStore.kt
│       ├── TransferErrorMapper.kt
│       └── TransferDiagnostics.kt
├── data/
│   ├── local/
│   │   ├── dao/
│   │   ├── entities/
│   │   ├── LocalDataSource.kt
│   │   └── DatabaseTransactionRunner.kt
│   ├── remote/
│   │   ├── api/
│   │   ├── dto/
│   │   └── RemoteDataSource.kt
│   ├── providers/
│   │   ├── CloudProvider.kt
│   │   ├── ProviderCapabilities.kt
│   │   ├── ProviderRegistry.kt
│   │   ├── ProviderAccountContext.kt
│   │   ├── TelegramProvider.kt
│   │   ├── GoogleDriveProvider.kt
│   │   ├── OneDriveProvider.kt
│   │   └── CloudyteProvider.kt
│   └── repository/
│       ├── FileRepositoryImpl.kt
│       ├── AccountRepositoryImpl.kt
│       ├── TransferRepositoryImpl.kt
│       └── CacheRepositoryImpl.kt
├── domain/
│   ├── model/
   ├── repository/
   └── usecase/
├── features/
│   ├── home/
│   ├── files/
│   ├── transfers/
│   ├── accounts/
│   ├── settings/
│   └── player/
└── navigation/
```

The existing `ui/screens` package may remain during migration. Each screen must progressively delegate to a feature ViewModel and domain use case rather than constructing API calls or workers directly.

---

## 4. Gap register and implementation plan

## Gap A — No single durable queue drainer

### Problem

Cloudyte has transfer rows, WorkManager workers, `TransferCoordinator`, `BackupWorker`, and `FileMutationWorker`, but the system does not yet have one authoritative queue drainer comparable to TeleDrive's queue worker. Multiple producers can compete, duplicate work, or apply different retry behavior.

### Files to create

```text
app/src/main/java/com/cloudyte/app/core/transfer/TransferQueueDrainer.kt
app/src/main/java/com/cloudyte/app/core/transfer/TransferScheduler.kt
app/src/main/java/com/cloudyte/app/core/transfer/TransferExecutionLease.kt
app/src/main/java/com/cloudyte/app/db/TransferQueueDao.kt
```

### Files to modify

```text
core/transfer/TransferCoordinator.kt
core/transfer/TransferWorker.kt
sync/BackupWorker.kt
sync/AutoSyncManager.kt
data/TransferRepository.kt
db/TransferDao.kt
AppContainer.kt
CloudyteApplication.kt
```

### Required contracts

```kotlin
interface TransferQueueDrainer {
    suspend fun drain(
        trigger: DrainTrigger,
        isStopped: () -> Boolean,
        onProgress: suspend (TransferProgressSnapshot) -> Unit
    ): DrainResult
}

interface TransferScheduler {
    fun kick(reason: ScheduleReason, allowMetered: Boolean)
    fun retrySoon(reason: ScheduleReason)
    fun schedulePeriodicMaintenance()
    fun cancelQueuedTransfer(transferId: String)
}

data class TransferExecutionLease(
    val transferId: String,
    val ownerId: String,
    val acquiredAt: Long,
    val expiresAt: Long
)
```

### Execution rules

1. Select runnable transfers ordered by priority, creation time, and dependency readiness.
2. Acquire a database lease atomically. A transfer with a valid lease cannot be taken by another worker.
3. Re-read the row after acquiring the lease.
4. Check cancellation, pause, network policy, authentication, and source-file existence.
5. Execute through the provider selected from account context.
6. Persist progress and checkpoint before acknowledging a chunk.
7. Release or renew the lease in a `finally` path.
8. Drain multiple transfers only within a bounded session. Do not let one large file starve the queue.
9. Return `RETRY` only for unresolved work or transient infrastructure failure.
10. Return success only when no runnable work remains.

### Acceptance criteria

- One unique WorkManager name: `cloudyte_transfer_queue`.
- Multiple enqueue calls coalesce.
- Two workers cannot execute the same transfer concurrently.
- A killed process leaves the row runnable after lease expiry.
- A paused transfer is never restarted by a periodic worker.
- A queue with mixed uploads/downloads reports aggregate notification progress.

---

## Gap B — Transfer state model is not sufficiently explicit

### Files to create/modify

```text
core/transfer/TransferState.kt
core/transfer/TransferCommand.kt
core/transfer/TransferProgress.kt
core/transfer/TransferErrorMapper.kt
core/transfer/TransferDiagnostics.kt
db/LocalTransfer.kt
db/TransferDao.kt
```

### Required state machine

```text
QUEUED
  -> WAITING_FOR_NETWORK
  -> WAITING_FOR_AUTH
  -> RUNNING
  -> PAUSING
  -> PAUSED
  -> CANCELLING
  -> CANCELLED
  -> COMPLETED
  -> FAILED_RETRYABLE
  -> FAILED_PERMANENT
  -> EXPIRED
```

Only these transitions are legal:

- `QUEUED -> RUNNING`, `WAITING_FOR_NETWORK`, or `WAITING_FOR_AUTH`.
- `RUNNING -> PAUSING`, `CANCELLING`, `COMPLETED`, `FAILED_RETRYABLE`, or `FAILED_PERMANENT`.
- `PAUSING -> PAUSED`.
- `CANCELLING -> CANCELLED`.
- `FAILED_RETRYABLE -> QUEUED` after retry policy permits it.
- No terminal state may transition back without a new operation ID or explicit retry action.

### Room additions for migration 9 -> 10

Add to `transfers`:

```text
state TEXT NOT NULL DEFAULT 'QUEUED'
priority INTEGER NOT NULL DEFAULT 0
operationType TEXT NOT NULL DEFAULT 'UPLOAD'
providerType TEXT
remoteId TEXT
parentFolderId TEXT
localUri TEXT
bytesPerSecond INTEGER NOT NULL DEFAULT 0
etaSeconds INTEGER
lastProgressAt INTEGER
lastAttemptAt INTEGER
attemptCount INTEGER NOT NULL DEFAULT 0
nextAttemptAt INTEGER
leaseOwner TEXT
leaseExpiresAt INTEGER
lastErrorCode TEXT
lastErrorMessage TEXT
lastHttpStatus INTEGER
correlationId TEXT
```

Preserve current columns and backfill `state` from the existing status column in the migration. Add indexes on `(state, priority, createdAt)`, `(leaseExpiresAt)`, and `(providerType, remoteId)`.

### Required DAO operations

```kotlin
suspend fun acquireNextRunnableLease(ownerId: String, now: Long, leaseUntil: Long): TransferEntity?
suspend fun renewLease(id: String, ownerId: String, leaseUntil: Long): Int
suspend fun releaseLease(id: String, ownerId: String): Int
suspend fun markWaitingForNetwork(id: String, reason: String, now: Long)
suspend fun markWaitingForAuth(id: String, reason: String, now: Long)
suspend fun transition(id: String, expected: TransferState, next: TransferState, ...)
fun observeActive(): Flow<List<TransferEntity>>
fun observeHistory(limit: Int): Flow<List<TransferEntity>>
```

---

## Gap C — Provider adapters are currently too thin

### Problem

Cloudyte has provider classes, but the transfer path defaults to `CloudyteProvider`. The registry is not yet the mandatory resolution point for every operation.

### Files to create/modify

```text
data/providers/ProviderCapabilities.kt
data/providers/ProviderAccountContext.kt
data/providers/CloudProvider.kt
data/providers/ProviderRegistry.kt
data/providers/ProviderError.kt
data/providers/ProviderOperation.kt
data/repository/AccountRepositoryImpl.kt
core/transfer/TransferEngine.kt
ui/screens/UploadHelper.kt
sync/BackupWorker.kt
```

### Required capability model

```kotlin
data class ProviderCapabilities(
    val supportsFolders: Boolean,
    val supportsRename: Boolean,
    val supportsDelete: Boolean,
    val supportsMove: Boolean,
    val supportsCopy: Boolean,
    val supportsServerSideSearch: Boolean,
    val supportsRemoteThumbnails: Boolean,
    val supportsLocalThumbnailGeneration: Boolean,
    val supportsMultipartUpload: Boolean,
    val supportsResumableUpload: Boolean,
    val supportsResumableDownload: Boolean,
    val supportsPauseResume: Boolean,
    val maxChunkBytes: Long?,
    val maxConcurrentRequests: Int,
    val requiresForegroundForLongTransfer: Boolean
)
```

### Required provider contract

```kotlin
interface CloudProvider {
    val type: ProviderType
    val capabilities: ProviderCapabilities

    suspend fun listChildren(context: ProviderAccountContext, folderId: String?): ProviderPage<RemoteFile>
    suspend fun createFolder(context: ProviderAccountContext, parentId: String?, name: String): RemoteFile
    suspend fun rename(context: ProviderAccountContext, remoteId: String, newName: String)
    suspend fun delete(context: ProviderAccountContext, remoteId: String)
    suspend fun move(context: ProviderAccountContext, remoteId: String, destinationId: String)
    suspend fun copy(context: ProviderAccountContext, remoteId: String, destinationId: String): RemoteFile
    suspend fun openUpload(context: ProviderAccountContext, request: UploadRequest): UploadSession
    suspend fun uploadPart(session: UploadSession, part: UploadPart): UploadPartResult
    suspend fun completeUpload(session: UploadSession): RemoteFile
    suspend fun abortUpload(session: UploadSession)
    suspend fun openDownload(context: ProviderAccountContext, request: DownloadRequest): DownloadSession
    suspend fun readDownload(session: DownloadSession, range: ByteRange?): DownloadPart
    suspend fun closeDownload(session: DownloadSession)
    suspend fun resolveThumbnail(context: ProviderAccountContext, remoteFile: RemoteFile, sizePx: Int): ThumbnailSource?
}
```

The implementation must never call `CloudyteApi` directly from a feature. Features resolve `ProviderAccountContext` from the account repository and pass it to the provider registry.

### Provider-specific behavior

- **Telegram:** message/document identity, part-based upload/download, provider flood-wait handling, media thumb retrieval, optional remote thumbnail generation.
- **Google Drive:** resumable upload session URI, server range query, 308 handling, Drive revision/ETag reconciliation, thumbnailLink handling.
- **OneDrive:** upload session URL, nextExpectedRanges parsing, conflict behavior, Graph download URL expiration, thumbnail endpoint.
- **Cloudyte:** Cloudyte upload session ID, checksum, idempotency key, offset reconciliation, server-side `/api/thumb` endpoint.

### Acceptance criteria

- Provider selection is based on the account row, not a default implementation.
- Every provider declares unsupported capabilities explicitly.
- Unsupported operations produce a typed `UnsupportedProviderOperation` state instead of a generic failure.
- A provider can be tested with a fake implementation without Retrofit or Android dependencies.

---

## Gap D — True provider-level resumability

### Files to create

```text
core/transfer/ResumeCheckpoint.kt
data/remote/dto/ProviderResumeDtos.kt
```

### Room additions for migration 10 -> 11

Create `transfer_checkpoints`:

```text
checkpointId TEXT PRIMARY KEY
transferId TEXT NOT NULL
providerType TEXT NOT NULL
providerSessionId TEXT
providerSessionUrl TEXT
nextOffset INTEGER NOT NULL DEFAULT 0
nextPartIndex INTEGER NOT NULL DEFAULT 0
chunkSize INTEGER NOT NULL
checksumAlgorithm TEXT
checksum TEXT
providerCursor TEXT
etag TEXT
lastConfirmedAt INTEGER
createdAt INTEGER NOT NULL
updatedAt INTEGER NOT NULL
```

Indexes:

```text
UNIQUE(transferId)
(providerType, providerSessionId)
```

### Resume rules

1. Create the provider session once per logical transfer.
2. Persist session identity before sending the first part.
3. Persist the provider-confirmed offset only after a successful provider response.
4. On retry, query or reconstruct the provider’s committed offset where supported.
5. Never infer committed bytes solely from local bytes written.
6. If the provider session expired, mark the checkpoint stale and create a new provider session while retaining the logical transfer ID and idempotency key.
7. If checksum mismatch occurs, restart only the affected provider session and record the reason.
8. Completion must be idempotent.

### Cloudyte server additions

Modify `server.ts` and server modules to guarantee:

- `POST /api/upload-sessions` accepts `idempotencyKey`, provider type, account ID, file metadata, checksum, and desired chunk size.
- Repeated creation with the same account and idempotency key returns the original session.
- `GET /api/upload-sessions/:sessionId` returns committed offset and status.
- `PUT /api/upload-sessions/:sessionId/chunks/:index` is idempotent for repeated chunks.
- Completion is idempotent and verifies checksum.
- Expired sessions return a typed `UPLOAD_SESSION_EXPIRED` error.
- The server does not report completion before the provider upload is durable.

---

## Gap E — Network policy is not centralized

### Files to create

```text
core/network/NetworkStatus.kt
core/network/NetworkMonitor.kt
core/network/NetworkPolicy.kt
core/network/ConnectivityNetworkMonitor.kt
core/network/TransferNetworkGate.kt
```

### Required model

```kotlin
enum class NetworkStatus { UNAVAILABLE, VALIDATED_METERED, VALIDATED_UNMETERED, CAPTIVE_PORTAL }

data class NetworkPolicySnapshot(
    val status: NetworkStatus,
    val allowMeteredTransfers: Boolean,
    val allowRoaming: Boolean,
    val requireChargingForBackup: Boolean,
    val batterySaverActive: Boolean
)

interface NetworkMonitor {
    val status: Flow<NetworkStatus>
    fun current(): NetworkStatus
}

interface NetworkPolicy {
    fun canStart(transfer: TransferEntity, snapshot: NetworkPolicySnapshot): PolicyDecision
}
```

### Behavior

- Browsing cached content is always allowed.
- Metadata refresh may run on validated metered networks unless user disables it.
- Large uploads/downloads default to unmetered only.
- User-requested foreground transfers may override metered restriction after explicit confirmation.
- Backup obeys charging, battery saver, roaming, and Wi-Fi preferences.
- Captive portal/unvalidated networks never start a transfer.
- When network is lost, running transfers persist progress and move to `WAITING_FOR_NETWORK`, not `FAILED_PERMANENT`.

### Files to modify

```text
sync/AutoSyncManager.kt
core/transfer/TransferScheduler.kt
core/transfer/TransferWorker.kt
ui/screens/SettingsSectionScreen.kt
UserPreferences.kt
```

Persist policy settings in DataStore with versioned keys and migration support.

---

## Gap F — Background lifecycle and process-death reliability

### Files to create

```text
core/transfer/TransferForegroundServicePolicy.kt
core/transfer/BootRecoveryReceiver.kt
core/transfer/WorkMaintenanceScheduler.kt
core/transfer/WorkResumeScheduler.kt
core/transfer/WorkerDiagnostics.kt
```

### Files to modify

```text
AndroidManifest.xml
CloudyteApplication.kt
core/transfer/TransferWorker.kt
sync/BackupWorker.kt
sync/AutoSyncManager.kt
```

### Required lifecycle flows

#### App startup

1. Initialize `ApiClient`, secure token store, Room, provider registry, cache manager, and network monitor.
2. Recover expired transfer leases.
3. Mark interrupted `RUNNING` rows as `QUEUED` or `WAITING_FOR_NETWORK` based on the last known policy.
4. Scan pending mutation outbox rows.
5. Schedule queue drain and maintenance work.

#### Boot completed

1. Do not immediately start large transfers.
2. Reconcile leases and stale workers.
3. Schedule constrained queue work.
4. Restore periodic backup only if enabled.

#### Worker process death

1. Lease expiry makes the transfer runnable.
2. Checkpoint remains valid.
3. The next worker queries provider session state.
4. UI observes the same Room row and shows “resuming” rather than resetting progress.

#### Long-running foreground work

Use a foreground data-sync notification for user-visible or long-running transfers. WorkManager remains the scheduler and lifecycle owner; foreground execution is an escalation policy, not a second transfer engine.

### Acceptance tests

- Kill the process during the first chunk, middle chunk, final completion, download stream, rename replay, and cache generation.
- Reboot with queued work.
- Disable network during an active transfer and restore it later.
- Change metered-network preference while a transfer is waiting.
- Ensure no duplicate provider objects or duplicate uploads are created.

---

## Gap G — Offline file browsing and reconciliation need formal semantics

### Files to create

```text
core/sync/ReconciliationPolicy.kt
core/sync/FolderSyncWorker.kt
data/local/entities/FolderSyncMeta.kt
data/local/dao/FolderSyncMetaDao.kt
```

### Room additions for migration 11 -> 12

Create `folder_sync_meta`:

```text
accountId TEXT NOT NULL
providerType TEXT NOT NULL
folderId TEXT NOT NULL
syncState TEXT NOT NULL
lastRequestedAt INTEGER
lastSuccessfulAt INTEGER
lastAttemptAt INTEGER
nextAttemptAt INTEGER
isCompleteSnapshot INTEGER NOT NULL DEFAULT 0
continuationToken TEXT
remoteRevision TEXT
lastErrorCode TEXT
lastErrorMessage TEXT
PRIMARY KEY(accountId, providerType, folderId)
```

Create `file_tombstones`:

```text
tombstoneId TEXT PRIMARY KEY
accountId TEXT NOT NULL
providerType TEXT NOT NULL
remoteId TEXT NOT NULL
folderId TEXT
deletedAt INTEGER NOT NULL
sourceRevision TEXT
```

### Reconciliation algorithm

- A folder listing is a snapshot only when the provider reports completion and no continuation token remains.
- Upsert received rows with `lastSeenAt` and remote revision.
- Do not delete missing rows until a complete snapshot is committed in the same transaction.
- Convert confirmed missing rows to tombstones before deletion if the UI or sync engine needs deletion history.
- Apply pending optimistic mutations after remote rows are merged, in creation order.
- If a pending delete exists, suppress the file from normal UI results but keep its mutation row.
- If a pending rename exists, display the optimistic name and expose a pending badge only where useful.
- Failed permanent mutations roll back their optimistic projection.

### Required UI states

Every file pane must support:

```text
CachedContent
RefreshingCachedContent
FreshContent
OfflineCachedContent
OfflineEmpty
EmptyRemoteFolder
ErrorWithCachedContent
ErrorWithoutContent
```

---

## Gap H — Thumbnail and preview caching is not yet TeleDrive-level

### Files to create

```text
core/cache/ThumbnailStore.kt
core/cache/PreviewStore.kt
core/cache/CacheQuotaManager.kt
core/cache/CacheKeyFactory.kt
core/cache/CacheCleanupWorker.kt
data/local/entities/ThumbnailEntity.kt
data/local/dao/ThumbnailDao.kt
```

### Room additions for migration 12 -> 13

Create `thumbnail_cache`:

```text
cacheKey TEXT PRIMARY KEY
accountId TEXT NOT NULL
providerType TEXT NOT NULL
remoteId TEXT NOT NULL
sourceRevision TEXT
path TEXT NOT NULL
mimeType TEXT NOT NULL
width INTEGER
height INTEGER
sizeBytes INTEGER NOT NULL
isEncrypted INTEGER NOT NULL DEFAULT 0
generatedAt INTEGER NOT NULL
lastAccessAt INTEGER NOT NULL
failureCount INTEGER NOT NULL DEFAULT 0
nextRetryAt INTEGER
```

Indexes:

```text
(accountId, lastAccessAt)
(providerType, remoteId)
(lastAccessAt)
```

### Cache tiers

1. **Memory:** Coil memory cache for visible thumbnails.
2. **Disk thumbnail cache:** durable generated or downloaded thumbnails, optionally encrypted.
3. **Preview cache:** short-lived larger media previews.
4. **Offline file cache:** user-pinned or automatically cached complete files.
5. **Metadata cache:** Room file index, never treated as binary content.

### Thumbnail flow

1. Check Room thumbnail row and verify file exists.
2. Touch `lastAccessAt` transactionally or with throttling.
3. If absent, check provider remote thumbnail endpoint.
4. If unavailable, use a local cached/offline file to generate a thumbnail.
5. If no local file exists and the provider supports a thumbnail request, download only the provider thumbnail bytes.
6. Limit generation concurrency with a semaphore.
7. Store output atomically through a temporary file and rename.
8. Encrypt at rest when the user setting requires it.
9. On repeated 404/not-supported responses, back off and avoid retrying every composition.
10. Invalidate when `sourceRevision`, remote modified time, or content hash changes.

### FileThumb changes

`ui/components/FileThumb.kt` must depend on `ThumbnailStore` through a ViewModel or injected UI image loader policy. It must not independently concatenate URLs, append tokens, or decide provider semantics. `ApiClient.getThumbUrl()` remains a compatibility adapter only.

### Cache quotas

Default quotas:

```text
metadata: Room-managed
thumbnails: 256 MB
previews: 512 MB
offline files: user-configurable, default 2 GB or 10% free storage, whichever is lower
```

Eviction order:

1. expired failed entries,
2. least-recently-used previews,
3. least-recently-used unpinned thumbnails,
4. least-recently-used offline files not opened in the configured retention period.

Never evict pinned offline files without explicit user action.

---

## Gap I — Offline mutation outbox needs conflict and idempotency policy

The uploaded update introduces `LocalFileMutation`, `FileMutationDao`, and `FileMutationWorker`. Complete it with the following rules.

### Files to modify

```text
db/LocalFileMutation.kt
db/FileMutationDao.kt
sync/FileMutationWorker.kt
data/CloudFileRepository.kt
```

### Required additions

Each mutation must contain:

```text
operationId
accountId
providerType
operation
remoteFileId
parentFolderId
payloadJson
createdAt
updatedAt
attemptCount
nextAttemptAt
status
lastErrorCode
lastErrorMessage
correlationId
```

The existing typed columns may remain for compatibility, but `payloadJson` is required for provider-specific options without future schema churn.

### Conflict policy

- Rename conflict: retain server name, mark `CONFLICT`, show resolution action.
- Delete of already-deleted file: treat as idempotent success.
- Create-folder duplicate: resolve by provider ID if returned; otherwise treat a provider “already exists” response as conflict.
- Move/copy conflict: do not silently overwrite unless the user explicitly selected overwrite.
- Unauthorized mutation: pause all mutations for the affected account and surface re-authentication.
- Permanent validation error: remove optimistic projection and mark mutation `FAILED_PERMANENT` with user-readable reason.

### Worker rules

- Process account-by-account to preserve ordering.
- Do not execute two mutations for the same remote file concurrently.
- Use exponential backoff with jitter.
- Do not use `APPEND_OR_REPLACE` if it can discard an already-running worker; use a queue policy that preserves active work.
- Refresh the affected folder only after successful or permanently resolved mutation.

---

## Gap J — Error taxonomy and retry policy

### Files to create

```text
core/common/AppError.kt
core/transfer/TransferErrorMapper.kt
core/transfer/RetryPolicy.kt
core/network/HttpErrorMapper.kt
```

### Error classes

```text
NetworkUnavailable
NetworkUnvalidated
AuthenticationRequired
AuthenticationExpired
RateLimited(retryAt)
ProviderUnavailable
ProviderSessionExpired
ChecksumMismatch
RemoteConflict
PermissionDenied
QuotaExceeded
UnsupportedOperation
SourceFileMissing
StorageUnavailable
CorruptLocalState
ServerProtocolError
Unknown
```

### Retry matrix

| Error | Retry | Backoff | User action |
|---|---:|---|---|
| Network unavailable | Yes | network reconnect | None |
| Timeout | Yes | exponential + jitter | None |
| HTTP 408/425/429/5xx | Yes | `Retry-After` or exponential | None |
| Auth 401 | No until auth restored | pause | Sign in |
| Permission 403 | No | none | Reconnect account/check permission |
| Quota exceeded | No | none | Free provider space/change account |
| Checksum mismatch | Limited | restart provider session | Retry, then report |
| Provider session expired | Yes | immediate session recreation | None |
| Remote conflict | No | none | Resolve conflict |
| Unsupported capability | No | none | Use supported provider/action |
| Missing local file | No | none | Re-select source |
| Disk full | No | none | Free storage |

Retries must be persisted in Room, not held only in worker memory.

---

## Gap K — UI state and navigation consistency

### Files to create/modify

```text
features/files/FilesUiState.kt
features/files/FilesViewModel.kt
features/home/HomeUiState.kt
features/home/HomeViewModel.kt
features/transfers/TransfersUiState.kt
features/transfers/TransfersViewModel.kt
navigation/AppNavHost.kt
navigation/NavigationTransitions.kt
ui/screens/FilesScreen.kt
ui/screens/SettingsSectionScreen.kt
ui/screens/TransfersScreen.kt
```

### Required UI state shape

Every feature exposes one immutable state containing:

```text
content
isInitialLoad
isRefreshing
isOffline
isStale
emptyReason
error
pendingOperationIds
lastUpdatedAt
```

The screen must render cached content immediately. A refresh indicator may appear alongside content but must not replace it.

### Navigation rules

- Bottom navigation contains Home, Files, Transfers, and Settings only.
- Account management is a Settings child route.
- Back from a Settings child returns to Settings.
- Back from Settings returns to the previous root destination, normally Home.
- Swipe-back and system back must use the same navigation stack.
- No screen may call a hard-coded “dashboard” navigation action for ordinary back behavior.
- Deep links into a file or transfer must preserve a valid parent stack.

### Loading rules

- Use `AppLoadingIndicator`/Material 3 `LoadingIndicator` for indeterminate state.
- Use a progress indicator for known transfer progress.
- Never show an infinite loading state when cached content exists.
- Every empty state must distinguish “empty remote folder” from “could not load folder.”

---

## Gap L — Transfer progress quality

### Files to modify

```text
core/transfer/TransferProgress.kt
core/transfer/TransferEngine.kt
data/TransferRepository.kt
db/TransferDao.kt
ui/screens/TransfersScreen.kt
```

### Required progress model

```kotlin
data class TransferProgressSnapshot(
    val transferId: String,
    val state: TransferState,
    val bytesTransferred: Long,
    val totalBytes: Long,
    val bytesPerSecond: Long,
    val etaSeconds: Long?,
    val currentPart: Int?,
    val totalParts: Int?,
    val lastCheckpointAt: Long,
    val retryCount: Int,
    val error: AppError?
)
```

Speed must be calculated from a rolling window, not a single delta. Use a minimum sample duration and remove outliers after retries. ETA is null when speed is unavailable or unstable.

Persist progress at a bounded interval, such as every 500–1500 ms or every chunk, whichever is later. UI may sample more frequently from a hot in-memory flow but must converge with Room.

Pause/resume must operate on the durable state machine. “Pause” cannot only cancel a coroutine; it must write `PAUSED` before the worker exits.

---

## Gap M — Authentication and account lifecycle

### Files to create/modify

```text
core/security/SecureTokenStore.kt
core/network/AuthInterceptor.kt
data/providers/ProviderAccountContext.kt
data/local/entities/LocalAccount.kt
data/local/dao/AccountDao.kt
ui/screens/AccountsScreen.kt
ui/screens/SettingsSectionScreen.kt
```

### Rules

- Store access and refresh credentials in encrypted storage, never in Room or logs.
- Each account has provider type, auth state, token expiry, last successful request, and last error.
- A 401 pauses only transfers for the affected account, not all accounts.
- Refresh-token failure marks the account `AUTH_REQUIRED`.
- Account removal cancels its pending work, removes provider session checkpoints, deletes account-scoped metadata and thumbnails, and securely handles offline files according to user confirmation.
- Account IDs must not be used as authorization proof; the server validates ownership on every request.

---

## Gap N — Server-side contract hardening

### Files to modify in the server repository

```text
server.ts
src/auth/*
src/transfers/*
src/providers/*
src/logging/*
src/limits/*
src/cache/*
```

If the current server is still organized as one `server.ts`, create the modules above and leave route adapters in `server.ts` only.

### Required server capabilities

1. Provider-neutral account and file identifiers.
2. Upload-session idempotency and offset reconciliation.
3. Per-account and per-user concurrency limits.
4. Rate limiting by account, user, IP, and operation class.
5. Structured logs with request ID, correlation ID, user ID, account ID, provider, operation, duration, status, and retryability.
6. OAuth state one-time use, expiration, and binding to the initiating user/session.
7. Production credential validation at startup.
8. No sensitive tokens in query strings where an Authorization header or signed short-lived URL is possible.
9. Thumbnail endpoint with authenticated access, provider-aware generation, bounded concurrency, cache headers, ETags, and typed 404 reasons.
10. Consistent error envelope:

```json
{
  "error": {
    "code": "UPLOAD_SESSION_EXPIRED",
    "message": "The upload session expired.",
    "retryable": true,
    "retryAfterSeconds": 30,
    "correlationId": "..."
  }
}
```

11. Provider sessions must never leak credentials in logs or error responses.
12. Server must clean abandoned upload sessions and thumbnail cache entries.

### Server-side tests required

- Duplicate idempotency key.
- Duplicate chunk.
- Out-of-order chunk.
- Chunk after session expiry.
- Checksum mismatch.
- Account ownership failure.
- Concurrent sessions over limit.
- Thumbnail cache hit/miss/generation failure.
- OAuth state replay.
- Process restart with active sessions.

---

## 5. Data migrations and rollout order

### Migration 9 -> 10: transfer state expansion

- Add transfer state, priority, operation type, provider, remote identity, lease, attempt, retry, speed, ETA, and correlation fields.
- Backfill state from existing status.
- Backfill provider type from the associated account where possible.
- Backfill unknown values safely as `UNKNOWN` or null.
- Add indexes.

### Migration 10 -> 11: resumable checkpoints

- Create `transfer_checkpoints`.
- For existing resumable transfer rows, copy session ID, chunk size, checksum, and next chunk fields into checkpoint rows.
- Do not delete legacy columns until at least one release has validated the migration in the field.

### Migration 11 -> 12: synchronization metadata

- Create `folder_sync_meta` and `file_tombstones`.
- Seed metadata for existing cached folders as stale but available.

### Migration 12 -> 13: thumbnail cache

- Create `thumbnail_cache`.
- Existing Coil disk files are not trusted as durable records; they may be lazily adopted or deleted.

### Migration rules

- Export Room schema JSON for every version.
- Add migration tests for every adjacent version.
- Test upgrade from the oldest supported production version, not only version 12.
- Never use destructive migration in production.
- Include a rollback strategy for application releases, even though Room schema rollback itself is not automatic.

---

## 6. WorkManager topology

Use these unique work names:

```text
cloudyte_transfer_queue
cloudyte_file_mutation_outbox
cloudyte_folder_sync
cloudyte_cache_cleanup
cloudyte_expired_session_cleanup
cloudyte_periodic_backup
cloudyte_diagnostics_upload (optional, opt-in only)
```

### Constraints

- Transfer queue: connected or unmetered according to policy; expedited for user-requested work when quota allows.
- File mutation outbox: connected; short backoff.
- Folder sync: connected; not expedited; coalesced by account/folder.
- Cache cleanup: charging or idle where appropriate; never block UI.
- Periodic backup: user policy, network policy, charging policy.

### Scheduling policy

A scheduler must inspect current WorkInfo before enqueueing. If active work exists, use `KEEP`; if stale or completed, use `REPLACE` with current constraints. Never leave obsolete Wi-Fi-only work waiting after the user changed the setting to allow mobile data.

---

## 7. Security, privacy, and storage requirements

- Encrypt tokens and account secrets with Android Keystore-backed storage.
- Support optional encrypted thumbnails and offline files.
- Use `FileProvider` for sharing local files; never expose raw filesystem paths.
- Ensure cache directories are app-private.
- Delete account-scoped cache on account removal.
- Redact Authorization headers, provider tokens, signed URLs, file paths containing user data, and request bodies from logs.
- Use certificate pinning only if operational rotation is defined; otherwise use standard TLS with strict hostname verification and monitoring.
- Apply screenshot blocking only according to the existing user preference; do not break media sharing or accessibility.
- Keep a privacy policy for offline caching and background backup behavior.

---

## 8. Observability and diagnostics

### Files to create

```text
core/transfer/TransferDiagnostics.kt
core/common/CorrelationId.kt
core/network/RequestCorrelationInterceptor.kt
ui/screens/DiagnosticsScreen.kt
```

### Required local diagnostic fields

- app version and build number,
- database schema version,
- provider type and account ID hash,
- transfer ID,
- correlation ID,
- operation type,
- current state,
- retry count,
- last error code/status,
- last checkpoint time,
- network state,
- worker run ID,
- cache hit/miss category.

Diagnostics must be exportable by the user without credentials or raw file content.

### Metrics

Track locally and optionally aggregate server-side:

- time to first cached file render,
- time to fresh folder render,
- transfer success rate,
- resume success rate,
- duplicate-session rate,
- average retry count,
- thumbnail hit rate,
- thumbnail generation failure rate,
- offline mutation resolution time,
- worker process-death recovery count.

---

## 9. Testing and production validation matrix

### Unit tests

- State-machine transition legality.
- Retry policy for every error class.
- Network policy decisions.
- Provider registry selection.
- Idempotency key reuse.
- Offset reconciliation.
- Cache key generation and eviction order.
- Optimistic mutation projection and rollback.
- Folder snapshot reconciliation.
- Speed and ETA rolling-window calculations.

### Room migration tests

- Every migration from the oldest supported schema.
- Null/default behavior for new columns.
- Unique constraints and indexes.
- Interrupted migration simulation where possible.

### Repository tests

- Cache-first success.
- Cache-first plus refresh success.
- Cache-first plus network failure.
- Complete snapshot deletion reconciliation.
- Partial page/continuation behavior.
- Provider capability unsupported behavior.
- Account-specific auth failure.

### Worker tests

- Worker killed before lease.
- Worker killed after lease.
- Worker killed after chunk upload and before checkpoint.
- Worker killed after checkpoint and before next chunk.
- Worker killed during completion.
- Network lost and restored.
- Auth expired mid-transfer.
- Disk full.
- Provider rate limit.
- Duplicate worker execution.

### Instrumented/device tests

- Android 8 through current supported API.
- Low-memory process recreation.
- Doze mode.
- Battery saver.
- Metered and unmetered networks.
- Roaming network.
- Captive portal.
- App upgrade across database versions.
- 10,000-file folder.
- 0-byte file.
- Very long names and Unicode names.
- Large files over multiple gigabytes.
- Multiple linked accounts.
- Thumbnail-heavy grid scrolling.
- Rotation and navigation back stack.
- Accessibility and large font settings.

### Failure budget before production

Do not release the transfer overhaul until:

- No duplicate transfer starts in concurrency tests.
- Resume works after process death for every provider that advertises resumability.
- No migration test fails.
- No uncategorized user-visible error remains for common network/auth/quota cases.
- Thumbnail cache hit rate and failure rate are measured on representative accounts.
- A staged rollout can be disabled through remote configuration or server-side account flags.

---

## 10. Implementation sequence

### Phase 1 — Contracts and state correctness

Implement transfer state machine, provider capability model, network models, error taxonomy, and migration 9 -> 10. Keep existing workers behind adapters.

**Exit:** all new operations compile against contracts; no new direct API calls from UI.

### Phase 2 — Queue drainer and scheduler

Introduce lease-based `TransferQueueDrainer`, unique queue scheduling, foreground escalation, and startup recovery. Convert `BackupWorker` into a producer or compatibility adapter.

**Exit:** one queue executes all transfer types and survives process death in tests.

### Phase 3 — Provider execution

Make `ProviderRegistry` mandatory. Implement provider-specific session creation, part upload, offset query, completion, cancellation, and capability errors.

**Exit:** each provider either passes its resumability tests or explicitly reports unsupported resumability.

### Phase 4 — Network policy and lifecycle

Add centralized connectivity monitoring, metered/charging/battery rules, boot recovery, maintenance work, and account-specific auth pausing.

**Exit:** network and lifecycle matrix passes on real devices.

### Phase 5 — Reconciliation and mutation outbox

Complete folder sync metadata, tombstones, mutation payloads, conflict states, ordered replay, and optimistic projection rules.

**Exit:** offline rename/delete/create/move behavior is deterministic and recoverable.

### Phase 6 — Thumbnail and cache subsystem

Introduce durable thumbnail records, local generation, provider thumbnails, encryption option, quotas, cleanup, and UI integration through `ThumbnailStore`.

**Exit:** thumbnails appear from remote, local, cached, and fallback sources without repeated request storms.

### Phase 7 — UI and diagnostics

Finish feature ViewModels, stable navigation stack, state-specific loading/empty/error/offline screens, transfer diagnostics, and accessibility polish.

**Exit:** UI never blocks on network when cache exists and every operation has a visible state.

### Phase 8 — Production hardening

Run migrations, process death, provider, server, security, performance, and staged rollout validation. Remove legacy direct paths only after telemetry confirms zero usage.

**Exit:** production release candidate with rollback and diagnostics plan.

---

## 11. Definition of done

Cloudyte is ready to claim production-grade parity or superiority in the relevant multi-provider scope only when all of the following are true:

- Every transfer enters one durable queue.
- Every transfer has a provider-specific session/checkpoint strategy.
- Provider capabilities and unsupported operations are explicit.
- Network policy is centralized and testable.
- Process death, reboot, Doze, battery saver, and network loss are handled.
- Room migrations are tested from all supported versions.
- Folder reconciliation distinguishes complete snapshots from partial pages.
- Offline mutations have conflict and idempotency semantics.
- Thumbnail and preview caching has durable metadata, quotas, cleanup, and optional encryption.
- UI state is cache-first and navigation-safe.
- Server APIs provide idempotency, correlation IDs, typed errors, rate limits, and secure thumbnail/session behavior.
- Production diagnostics can explain every failed transfer without exposing secrets.
- Real-device tests pass for each provider capability advertised by the app.

The strategic conclusion is to **adopt TeleDrive’s proven reliability patterns—queue draining, durable local entities, foreground transfer execution, network-aware scheduling, dedicated thumbnail storage, and lifecycle recovery—without adopting Telegram-specific assumptions**. Cloudyte’s provider-neutral contract and server-backed resumable sessions remain the differentiating architecture.
