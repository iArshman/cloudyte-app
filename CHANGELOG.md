# Cloudyte Android client changelog

Date: 2026-11-01
Target: `itxrex07/cd`, branch `main`

## Phase 1 — Contracts and state correctness

- Added provider-independent transfer states, capability and policy models, retry metadata, lease ownership, resumable checkpoints, idempotency fields, and owner-fenced Room updates.
- Added Room migration `9 -> 10`, schema export support, and migration coverage.

## Phase 2 — Queue drainer and scheduler

- Added the lease-based transfer queue drainer and shared execution path for uploads, downloads, and backups.
- Added unique WorkManager scheduling, startup recovery, delayed lease expiry recovery, and foreground transfer support.
- Routed upload, text-save, and offline-download flows through the durable queue.

## Phase 3 — Provider execution

- Made provider selection account-driven and fail explicitly for unsupported or mismatched providers.
- Implemented `GoogleDriveProvider`, `OneDriveProvider`, `TelegramProvider`, and `CloudyteProvider` as server-backed capability adapters over `CloudyteApi` (`/upload/session`, `/upload/session/{id}/chunk`, `/upload/session/{id}/complete`, and ranged `/files/{id}/download`).
- Separated server-staged resumability (`supportsResumableUpload = true`) from upstream provider-native resumability (`supportsProviderNativeResumability`), enabling chunked resumable uploads for `CloudyteProvider` and `TelegramProvider` while accurately reporting that only `GoogleDriveProvider` and `OneDriveProvider` expose upstream provider-native resumability and session cancellation.
- Added durable session cancellation fencing, checksum validation, idempotent completion, and provider capability signals.

## Phase 4 — Network policy and lifecycle recovery

- Added centralized connectivity monitoring for unavailable, captive, validated metered, and validated unmetered networks, including roaming, charging, battery-saver, and low-battery signals.
- Added policy-gated transfer states for network and authentication pauses.
- Added boot recovery, periodic lifecycle maintenance, coalesced queue recovery, persisted roaming preference, and locked-boot manifest registration.

## Phase 5 — Reconciliation and mutation outbox

- Advanced Room through schema version 12 with durable mutation payloads, provider and remote identifiers, retry/backoff state, conflict and permanent-failure states, correlation IDs, and account-specific authentication pauses.
- Added ordered mutation replay with claim fencing, idempotent delete handling, explicit conflict handling, and optimistic projection support.
- Added complete-snapshot folder reconciliation, tombstones, continuation/revision metadata, and a provider-scoped constrained folder sync worker (`cloudyte_folder_sync_{accountId}_{providerType}_{folderId}`). Partial or failed listings persist `continuationToken` across runs and never delete absent cached files unless `isCompleteSnapshot == true`.

## Phase 6 — Thumbnail and cache subsystem

- Added durable Room `thumbnail_cache` records and migration `12 -> 13`, with account/provider/revision-aware keys, access timestamps, dimensions, failure backoff, and file metadata.
- Added `ThumbnailStore` as the single UI/cache policy boundary with bounded concurrency (`Semaphore(4)`) and per-key in-flight `Deferred` deduplication, offline/local file thumbnail generation wired through `FileThumb`, and preview URL fallback.
- Added bounded thumbnail and preview stores, true LRU access-time tracking in `PreviewStore.get()`, quota-aware LRU cleanup, stale failure cleanup, account-scoped cache deletion, and the unique WorkManager job `cloudyte_cache_cleanup`.
- Routed `FileThumb` through `ThumbnailStore`; UI no longer builds provider URLs or appends authentication tokens directly.
- Added cache-key isolation and migration tests.

## Phase 7 — UI and diagnostics

- Added a user-facing diagnostics screen reachable from Settings with redacted copy/share export. Reports include app/build and schema versions, hashed account identity, provider, transfer state, retry/error details, checkpoint time, worker run ID, cache category, and correlation IDs without credentials, raw URLs, file names, or content.
- Added request correlation headers through a centralized OkHttp interceptor and bounded in-memory transfer diagnostics with structured queue-policy and transfer-outcome events.
- Added rolling-window transfer speed and ETA calculation with minimum sample duration and outlier suppression, and routed upload/download progress through it.
- Added the diagnostics entry point and preserved stable back navigation for the Settings UI.

## Phase 8 — Production hardening & audit fixes

- Added a persisted, fail-safe `TransferRolloutGate` kill switch. Disabling rollout stops new queue drains without deleting durable transfer rows, allowing rollback and later resume.
- Changed unexpected/transient transfer error mapping to default to `TransferOutcome.Retryable`, reserving `TransferOutcome.Permanent` for explicitly unrecoverable errors (e.g., HTTP 403/404, missing local source file, quota exceeded, unsupported provider operation).
- Implemented true resumable downloads via `ProviderRegistry` using `.part` temporary files, persisted byte checkpoints, `Range: bytes=<offset>-` request headers, HTTP `206 Partial Content` vs `200 OK` handling, and atomic rename on completion.
- Wired active transfer pause and cancellation (`TransferScheduler.cancelQueuedTransfer`, `ActiveTransferSignalRegistry`) to immediately interrupt in-flight chunk loops, transition Room states deterministically, and invoke `provider.cancelUploadSession`.
- Exported Room schemas `11.json`, `12.json`, and `13.json` matching `AppDatabase` version 13.
- Redacted upload and offline-download logs so release logging does not emit raw response bodies, exception payloads, file names, or file contents.
