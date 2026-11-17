package com.cloudyte.app.sync

import android.content.Context
import android.database.ContentObserver
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.util.Log
import com.cloudyte.app.data.UserPreferences
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean

/**
 * In-process ContentObserver watching MediaStore image/video URIs without needing
 * broad storage permissions. Applies a 2-second settle debounce so camera bursts
 * or screen-recorder flushes coalesce into a single BackupWorker scan.
 */
object MediaStoreWatcher {
    private const val TAG = "MediaStoreWatcher"
    const val DEFAULT_DEBOUNCE_MS = 2_000L

    private val registered = AtomicBoolean(false)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var appContextRef: Context? = null
    private var debounceJob: Job? = null
    private var observer: ContentObserver? = null

    fun start(context: Context) {
        val appContext = context.applicationContext
        appContextRef = appContext
        if (!registered.compareAndSet(false, true)) return

        val handler = Handler(Looper.getMainLooper())
        val contentObserver = object : ContentObserver(handler) {
            override fun onChange(selfChange: Boolean) {
                onChange(selfChange, null)
            }

            override fun onChange(selfChange: Boolean, uri: Uri?) {
                notifyChanged(appContext)
            }
        }
        observer = contentObserver

        runCatching {
            appContext.contentResolver.registerContentObserver(
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                true,
                contentObserver
            )
            appContext.contentResolver.registerContentObserver(
                MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
                true,
                contentObserver
            )
        }.onFailure { err ->
            registered.set(false)
            Log.w(TAG, "Unable to register MediaStore ContentObserver: ${err.message}")
        }
    }

    fun stop(context: Context) {
        debounceJob?.cancel()
        debounceJob = null
        val obs = observer
        observer = null
        if (registered.compareAndSet(true, false) && obs != null) {
            runCatching { context.applicationContext.contentResolver.unregisterContentObserver(obs) }
        }
    }

    internal fun notifyChanged(
        context: Context,
        debounceMs: Long = DEFAULT_DEBOUNCE_MS,
        onSettled: suspend (Context) -> Unit = { ctx ->
            val prefs = runCatching { UserPreferences.observe(ctx).first() }.getOrNull()
            if (prefs != null && prefs.autoBackupEnabled && prefs.quickMediaBackupEnabled) {
                AutoSyncManager.enqueueImmediateSync(
                    context = ctx,
                    prefsOverride = prefs,
                    forceReupload = false,
                    isManual = false
                )
            }
        }
    ) {
        val appContext = context.applicationContext
        debounceJob?.cancel()
        debounceJob = scope.launch {
            delay(debounceMs.coerceAtLeast(0L))
            runCatching { onSettled(appContext) }
        }
    }
}
