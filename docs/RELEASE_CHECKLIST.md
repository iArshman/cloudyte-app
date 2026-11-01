# Cloudyte Android release checklist

## Build and migration validation

- Run `./gradlew :app:assembleDebug` with a full JDK (including `javac`) and Android SDK.
- Run `./gradlew :app:testDebugUnitTest`.
- Run `./gradlew :app:lint` and resolve release-blocking findings.
- Verify Room migrations from every supported schema version through version 8.
- Confirm the generated schema artifact for `com.cloudyte.app.db.AppDatabase` is committed.

## Transfer reliability

- Kill the process during an active transfer and confirm the transfer returns to `PENDING`.
- Retry a failed transfer and confirm the attempt counter increases.
- Confirm idempotency keys remain stable across retries.
- Confirm persisted provider session, chunk index, checksum, and byte offset are restored.
- Test pause, resume, cancel, and retry while the app is backgrounded.
- Test metered network, offline startup, and network recovery.

## Security and data safety

- Confirm auth tokens remain encrypted and are excluded from backups.
- Confirm upload checksum and idempotency headers do not expose credentials.
- Confirm release logging does not include bearer tokens or file contents.
- Verify notification permission behavior on Android 13+.
- Verify release logs contain no bearer tokens, raw response bodies, URLs with credentials, file names, or file contents.
- Verify backup and device-transfer rules exclude encrypted preferences, legacy token preferences, the Room database, and offline vault files.

## Production hardening and staged rollout

- Start with the transfer rollout gate enabled for an internal account cohort only; compare transfer success, retry, resume, auth-wait, network-wait, and thumbnail-failure diagnostics against the previous release.
- If failure budgets are exceeded, disable `TransferRolloutGate` through the remote-config/support adapter. Disabling must stop new queue drains while preserving durable rows for rollback and later resume.
- Keep the previous APK and server configuration available for rollback. Do not use destructive Room migration or delete legacy transfer columns until one production release has validated the upgrade path.
- Validate process death before lease acquisition, after lease acquisition, after checkpoint persistence, and during completion. Confirm expired leases are recovered and idempotency keys are unchanged.
- Validate Android 8 through the current API, Doze, battery saver, metered/roaming/captive networks, notification permission, large fonts, rotation, and back-stack restoration.
- Capture the exported diagnostics report for every release-blocking failure; confirm it is credential-free before attaching it to an incident.

## Release smoke test

- Fresh install and first login.
- Upgrade from the previous production database.
- Browse cached files with the server unavailable.
- Upload a small file and a large file.
- Rename, delete, move, and share a file.
- Reopen the app after process death.
- Verify foreground transfer notification and final transfer history.

## Rollback record

- Release commit and APK:
- Server release/configuration:
- Rollout cohort and start time:
- Rollback trigger and owner:
- Rollback completed at:
- Durable queue rows preserved: yes / no
