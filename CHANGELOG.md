# Cloudyte transfer work — handoff changelog

Date: 2026-11-01
Target: `itxrex07/CD`, branch `main`
Base commit: `b75cb67` (`Merge updated client and fix thumbnails`)

## Scope completed in this checkpoint

This change implements the blueprint's **Phase 1 — Contracts and state correctness** and **Phase 2 — Queue drainer and scheduler** foundations. It does not claim that later phases or production readiness are complete.

### Phase 1: contracts and state correctness

- Added a provider-independent transfer state model and legacy status mapping, including recovery of lease-less legacy `RUNNING` work into a queued/retryable state.
- Added provider capability and transfer policy/network/retry models.
- Expanded the Room transfer record and DAOs for durable queue state, operation/provider metadata, idempotency, lease ownership/expiry, retry scheduling, resumable checkpoints, and owner-fenced updates.
- Added Room migration **9 → 10**, exported schema `app/schemas/com.cloudyte.app.db.AppDatabase/10.json`, and a real SQLite migration test fixture.
- Kept account/provider transport behind application/data adapters; no server-side code or API deployment is included in this repository change.

### Phase 2: queue drainer and scheduler

- Added a lease-based Room queue drainer with owner fencing and a shared executor path for upload, download, and backup transfer rows.
- Added unique WorkManager scheduling, startup queue kicking, delayed lease-expiry recovery, and foreground transfer notification support.
- Converted `BackupWorker` into a queue producer/compatibility path and routed the affected Home upload, text save, and offline-file download flows through the durable queue.
- Made application startup tolerate an unavailable WorkManager instance (notably in Robolectric); queued work remains durable and can be scheduled on a later trigger.

## Verification status

- **Main app Kotlin/Room compilation:** passed during the unit-test task.
- **Focused Phase 1–2 tests:** passed after correcting the v9 migration test fixture. The final focused command selected `TransferMigrationTest`, `TransferQueueDaoTest`, `TransferStateTest`, `TransferQueueDrainerTest`, and two `CloudFileRepositoryTest` cases, including the cache-preservation regression.
- **Full `:app:testDebugUnitTest` suite:** not fully verified. After the WorkManager startup guard, the run reached 25 tests and found that `refresh_preservesQueuedRenameDeleteAndCreateFolderOverServerSnapshot` expected a set containing `keep` even though the test had renamed that row to `After.txt` and asserted that name immediately afterward. The expected set was corrected in this change, and the focused rerun passed. The full suite was stopped before completion and was not rerun after that correction; do not interpret the focused pass as a green full suite.
- Earlier full-suite attempt, before the startup guard, produced Robolectric failures because WorkManager was unavailable during `Application.onCreate`; that startup path is now guarded, and the focused repository test passes.

## Not implemented / remaining

- **Phase 4:** full connectivity/lifecycle policy is not finished (central monitoring, boot recovery, charging/battery rules, and account-specific auth pausing still need validation).
- **Phases 5–8:** mutation reconciliation/outbox completeness, thumbnail/cache subsystem, remaining UI/diagnostics/accessibility work, and production/security/performance/staged-rollout validation are not completed by this change.
- Run the complete Android unit-test suite and an Android debug build, then perform device-level process-death, provider-auth, cancellation-race, and migration checks before treating this as release-ready.

## Original blueprint

`CloudyteProductionImplementationBlueprint.md` is included alongside this file as an unchanged copy of the original recovered blueprint (SHA-256: `bfb47fad8fce2f46e5e3c1bd6178254db086c31cb336bc5b6c80d3c5acd5fa27`).

## Phase 3: provider-specific execution and server reconciliation

- Provider lookup is exact and account-driven; unknown/missing account providers fail with typed `UnsupportedProviderOperation` rather than silently falling through. Cloudyte's pseudo-adapter now explicitly rejects resumable upload because the server has no Cloudyte storage account type.
- The client sends the selected provider type and desired chunk size, reconciles session offsets with the server, preserves the original idempotency key after expiry, and calls the server-confirmed cancellation endpoint. Server-staged resumability remains available for Telegram, Google Drive, and OneDrive; native provider resumability/cancellation are represented as separate capabilities.
- Google Drive uses a persisted native resumable-session URI, 308/`Range` offset queries, 256-KiB-aligned 8-MiB parts, and an `appProperties` transfer idempotency marker. OneDrive persists its Graph `uploadUrl`, queries `nextExpectedRanges`, reconciles offsets after retry/process restart, renews expired sessions, and cancels a persisted active upload session.
- Telegram continues to use GramJS `sendFile`; metadata lookup makes completion retries idempotent after the server-side file record is written. Telegram does **not** advertise native provider-session resumability or mid-finalization cancellation; its Cloudyte staging upload remains resumable. Telegram zero-byte document uploads return a typed unsupported-capability error. This distinction is included in the session response (`providerResumable` / `providerCancellable`).
- Staged chunks now accept byte-identical retries after a lost response, reject mismatched replays, fence concurrent chunk writes, and truncate uncommitted trailing bytes after a process crash. Provider completion is lease-fenced and can reclaim stale work; checksum validation precedes provider commit. OneDrive cancellation is fenced before aborting its provider session; Google/Telegram cancellation during provider commit is rejected rather than falsely marked complete/cancelled.
- Server verification: `node --check server.ts`, `npm run lint`, and four parser tests (`npm test`) pass. Android focused tests were added for provider aliases/capability flags, but could not run in this environment because no Android SDK is installed. Real provider-auth/upload/resume checks still need a linked test account; no credentials are included here.
