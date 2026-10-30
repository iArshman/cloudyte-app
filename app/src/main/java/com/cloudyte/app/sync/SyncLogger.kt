package com.cloudyte.app.sync

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

enum class SyncLogLevel {
    INFO,
    SUCCESS,
    WARNING,
    ERROR
}

enum class SyncState {
    IDLE,
    SCANNING,
    SYNCING,
    SUCCESS,
    ERROR
}

data class SyncLog(
    val id: Long = System.currentTimeMillis(),
    val timeFormatted: String,
    val message: String,
    val level: SyncLogLevel = SyncLogLevel.INFO
)

data class SyncStatusInfo(
    val state: SyncState = SyncState.IDLE,
    val lastSyncTime: Long = 0L,
    val lastSyncSummary: String = "No backup performed yet",
    val currentAction: String = "",
    val currentFile: String = "",
    val uploadedFilesCount: Int = 0,
    val totalFilesCount: Int = 0
)

object SyncLogger {
    private const val PREFS_NAME = "sync_logger_prefs"
    private const val KEY_LOGS = "cached_logs"
    private const val MAX_LOGS = 300

    private val timeFormat = SimpleDateFormat("HH:mm:ss", Locale.getDefault())
    private val _logsFlow = MutableStateFlow<List<SyncLog>>(emptyList())
    val logsFlow: StateFlow<List<SyncLog>> = _logsFlow.asStateFlow()

    private val _statusFlow = MutableStateFlow(SyncStatusInfo())
    val statusFlow: StateFlow<SyncStatusInfo> = _statusFlow.asStateFlow()

    private var prefs: SharedPreferences? = null

    fun init(context: Context) {
        if (prefs == null) {
            prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            loadLogsFromPrefs()
        }
    }

    private fun loadLogsFromPrefs() {
        val rawJson = prefs?.getString(KEY_LOGS, null) ?: return
        try {
            val jsonArray = JSONArray(rawJson)
            val list = mutableListOf<SyncLog>()
            for (i in 0 until jsonArray.length()) {
                val obj = jsonArray.getJSONObject(i)
                list.add(
                    SyncLog(
                        id = obj.optLong("id", System.currentTimeMillis()),
                        timeFormatted = obj.optString("time", ""),
                        message = obj.optString("message", ""),
                        level = try {
                            SyncLogLevel.valueOf(obj.optString("level", "INFO"))
                        } catch (_: Exception) {
                            SyncLogLevel.INFO
                        }
                    )
                )
            }
            _logsFlow.value = list
        } catch (_: Exception) {
        }
    }

    private fun saveLogsToPrefs(list: List<SyncLog>) {
        try {
            val jsonArray = JSONArray()
            val takeList = list.takeLast(100)
            for (item in takeList) {
                val obj = JSONObject()
                obj.put("id", item.id)
                obj.put("time", item.timeFormatted)
                obj.put("message", item.message)
                obj.put("level", item.level.name)
                jsonArray.put(obj)
            }
            prefs?.edit()?.putString(KEY_LOGS, jsonArray.toString())?.apply()
        } catch (_: Exception) {
        }
    }

    @Synchronized
    fun log(message: String, level: SyncLogLevel = SyncLogLevel.INFO) {
        val entry = SyncLog(
            id = System.currentTimeMillis() + (0..999).random(),
            timeFormatted = timeFormat.format(Date()),
            message = message,
            level = level
        )
        val current = _logsFlow.value.toMutableList()
        current.add(entry)
        if (current.size > MAX_LOGS) {
            current.removeAt(0)
        }
        _logsFlow.value = current
        saveLogsToPrefs(current)
    }

    fun updateStatus(
        state: SyncState,
        action: String = "",
        file: String = "",
        uploadedCount: Int = _statusFlow.value.uploadedFilesCount,
        totalCount: Int = _statusFlow.value.totalFilesCount,
        summary: String? = null
    ) {
        val prev = _statusFlow.value
        _statusFlow.value = prev.copy(
            state = state,
            currentAction = action,
            currentFile = file,
            uploadedFilesCount = uploadedCount,
            totalFilesCount = totalCount,
            lastSyncTime = if (state == SyncState.SUCCESS || state == SyncState.ERROR) System.currentTimeMillis() else prev.lastSyncTime,
            lastSyncSummary = summary ?: prev.lastSyncSummary
        )
    }

    @Synchronized
    fun clearLogs() {
        _logsFlow.value = emptyList()
        prefs?.edit()?.remove(KEY_LOGS)?.apply()
    }
}
