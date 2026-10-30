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

## Release smoke test

- Fresh install and first login.
- Upgrade from the previous production database.
- Browse cached files with the server unavailable.
- Upload a small file and a large file.
- Rename, delete, move, and share a file.
- Reopen the app after process death.
- Verify foreground transfer notification and final transfer history.
