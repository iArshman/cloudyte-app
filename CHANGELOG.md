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
- Added provider-specific resumable execution and offset reconciliation for Google Drive and OneDrive.
- Preserved Telegram staging resumability while explicitly reporting that native Telegram resumability and cancellation are unsupported.
- Added durable session cancellation fencing, checksum validation, idempotent completion, and provider capability signals.

## Phase 4 — Network policy and lifecycle recovery

- Added centralized connectivity monitoring for unavailable, captive, validated metered, and validated unmetered networks, including roaming, charging, battery-saver, and low-battery signals.
- Added policy-gated transfer states for network and authentication pauses.
- Added boot recovery, periodic lifecycle maintenance, coalesced queue recovery, persisted roaming preference, and locked-boot manifest registration.

## Phase 5 — Reconciliation and mutation outbox

- Advanced Room through schema version 12 with durable mutation payloads, provider and remote identifiers, retry/backoff state, conflict and permanent-failure states, correlation IDs, and account-specific authentication pauses.
- Added ordered mutation replay with claim fencing, idempotent delete handling, explicit conflict handling, and optimistic projection support.
- Added complete-snapshot folder reconciliation, tombstones, continuation/revision metadata, and a constrained folder sync worker. Partial listings never delete absent cached files.

## Phase 6 — Thumbnail and cache subsystem

- Added durable Room `thumbnail_cache` records and migration `12 -> 13`, with account/provider/revision-aware keys, access timestamps, dimensions, failure backoff, and file metadata.
- Added `ThumbnailStore` as the single UI/cache policy boundary. It checks durable records first, atomically stores provider thumbnails, can generate thumbnails from local files, falls back to preview URLs, and serializes concurrent requests to avoid composition request storms.
- Added bounded thumbnail and preview stores, quota-aware LRU cleanup, stale failure cleanup, account-scoped cache deletion, and the unique WorkManager job `cloudyte_cache_cleanup`.
- Routed `FileThumb` through `ThumbnailStore`; UI no longer builds provider URLs or appends authentication tokens directly.
- Added cache-key isolation and migration tests.

## Verification

- Server syntax, lint, and protocol tests pass for the existing provider contract.
- Client whitespace and static checks pass.
- Android compilation, Room schema generation, and device-level migration/cache tests require an Android SDK-enabled environment; the current sandbox does not provide an Android SDK.
