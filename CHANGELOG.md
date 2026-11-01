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

- **Phase 3:** provider-specific protocol completeness is not finished. Verify per-provider resumability, session recovery/offset queries, completion/cancellation, and unsupported-capability behavior.
- **Phase 4:** full connectivity/lifecycle policy is not finished (central monitoring, boot recovery, charging/battery rules, and account-specific auth pausing still need validation).
- **Phases 5–8:** mutation reconciliation/outbox completeness, thumbnail/cache subsystem, remaining UI/diagnostics/accessibility work, and production/security/performance/staged-rollout validation are not completed by this change.
- Run the complete Android unit-test suite after diagnosing the named existing cache-preservation failure; also run an Android debug build and device-level process-death/migration checks before treating this as release-ready.

## Original blueprint

`CloudyteProductionImplementationBlueprint.md` is included alongside this file as an unchanged copy of the original recovered blueprint (SHA-256: `bfb47fad8fce2f46e5e3c1bd6178254db086c31cb336bc5b6c80d3c5acd5fa27`).

## Phase 3: provider execution (client-side contract)

- Made provider lookup exact and account-driven. Known aliases normalize to Telegram, Google Drive, OneDrive, or Cloudyte; an unknown or missing provider now yields `UnsupportedProviderOperation` instead of silently routing to Cloudyte.
- Extended the provider boundary with server-confirmed upload-session status/offset reconciliation and explicit upload-session cancellation. The resumable upload flow queries the session after idempotent creation and uses the returned committed byte offset; expired sessions are surfaced as an expired-session response instead of being treated as local progress.
- Added the Android API contract for `GET /api/upload-sessions/{sessionId}` and `DELETE /api/upload-sessions/{sessionId}`. Unsupported provider operations map to a dedicated `PROVIDER_UNSUPPORTED_OPERATION` transfer error. Resumable downloads remain explicitly unsupported.
- Paired server changes in `itxrex07/cloudytex` add authenticated session status/offset lookup, 24-hour expiry, cancellation with staged-file cleanup, and idempotent completion responses. The client renews expired sessions with the original idempotency key. The server continues routing completed file contents to the account-selected provider adapter. Provider-native Google Drive 308/Range, OneDrive `nextExpectedRanges`, and Telegram part/session protocols remain server-adapter-specific and are not implemented as direct client SDK integrations; end-to-end live-provider acceptance still requires integration testing.
