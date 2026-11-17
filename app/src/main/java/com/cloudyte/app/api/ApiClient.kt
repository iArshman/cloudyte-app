package com.cloudyte.app.api

import android.content.Context
import android.content.SharedPreferences
import android.net.Uri
import android.util.Log
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.cloudyte.app.BuildConfig
import com.cloudyte.app.core.network.RequestCorrelationInterceptor
import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import okhttp3.Cache
import okhttp3.ConnectionPool
import okhttp3.Dispatcher
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.converter.moshi.MoshiConverterFactory
import java.io.File
import java.util.concurrent.TimeUnit

object ApiClient {
    private const val TOKEN_KEY = "auth_token"
    private const val SECURE_PREFS_NAME = "cloudyte_secure_prefs"
    // Configurable API URL
    const val API_BASE_URL = "https://drive.arshman.me"

    private val apiHost: String by lazy { API_BASE_URL.toHttpUrl().host }
    private val querySecretRegex = Regex("""(?i)([?&](?:token|sig|access_token|refresh_token|password|passphrase|secret|api_key)=)([^&\s]+)""")

    private var sharedPreferences: SharedPreferences? = null

    @Volatile
    private var inMemoryToken: String? = null

    private data class CachedMediaLink(
        val url: String,
        val expiresAtMs: Long
    )

    private val signedMediaCache = java.util.concurrent.ConcurrentHashMap<String, CachedMediaLink>()

    fun saveToken(token: String?) {
        inMemoryToken = token
        if (token.isNullOrBlank()) {
            signedMediaCache.clear()
        }
        sharedPreferences?.edit()?.putString(TOKEN_KEY, token)?.apply()
    }

    fun getToken(): String? {
        return sharedPreferences?.getString(TOKEN_KEY, null) ?: inMemoryToken
    }

    private val authInterceptor = Interceptor { chain ->
        val request = chain.request()
        val path = request.url.encodedPath
        val requestBuilder = request.newBuilder()
        val token = getToken()
        val isTrustedOrigin = request.url.isHttps && request.url.host.equals(apiHost, ignoreCase = true)
        if (isTrustedOrigin && !token.isNullOrEmpty() && !path.endsWith("/login") && !path.endsWith("/register")) {
            requestBuilder.header("Authorization", "Bearer $token")
        }
        chain.proceed(requestBuilder.build())
    }

    fun parseErrorEnvelope(errorBody: String?, statusCode: Int, isRegister: Boolean = false): ParsedApiError {
        var parsedCode: String? = null
        var parsedRequestId: String? = null
        var parsedRetryable: Boolean? = null
        if (!errorBody.isNullOrBlank()) {
            try {
                val json = org.json.JSONObject(errorBody)
                parsedCode = json.optString("code").takeIf { it.isNotBlank() }
                parsedRequestId = json.optString("requestId").takeIf { it.isNotBlank() }
                if (json.has("retryable") && !json.isNull("retryable")) {
                    parsedRetryable = json.optBoolean("retryable")
                }
                val nestedErrObj = json.optJSONObject("error")
                if (parsedCode == null && nestedErrObj != null) {
                    parsedCode = nestedErrObj.optString("code").takeIf { it.isNotBlank() }
                }
                val nestedErrMsg = nestedErrObj?.let { obj ->
                    obj.optString("message").takeIf { it.isNotBlank() }
                        ?: obj.optJSONArray("errors")?.optJSONObject(0)?.optString("message")?.takeIf { it.isNotBlank() }
                        ?: obj.optString("error_description").takeIf { it.isNotBlank() }
                }
                val topLevelErr = if (nestedErrObj == null) {
                    json.optString("error").takeIf { it.isNotBlank() && !it.trimStart().startsWith("{") }
                } else null
                val err = nestedErrMsg
                    ?: topLevelErr
                    ?: json.optString("message").takeIf { it.isNotBlank() }
                    ?: json.optString("msg").takeIf { it.isNotBlank() }
                    ?: json.optJSONArray("errors")?.optJSONObject(0)?.optString("message")?.takeIf { it.isNotBlank() }
                if (!err.isNullOrBlank()) {
                    return ParsedApiError(
                        message = err,
                        code = parsedCode,
                        requestId = parsedRequestId,
                        retryable = parsedRetryable
                    )
                }
            } catch (_: Exception) {
                if (errorBody.length < 150 && !errorBody.contains("<html", ignoreCase = true) && !errorBody.trimStart().startsWith("{")) {
                    return ParsedApiError(message = errorBody.trim())
                }
            }
        }
        val fallbackMessage = when (statusCode) {
            401 -> if (isRegister) "Registration unauthorized. Please try a different username." else "Session expired or unauthorized. Please sign in again."
            400 -> "Invalid request (HTTP 400). Please check and try again."
            403 -> "Access denied by cloud provider (HTTP 403)."
            404 -> "File or resource not found (HTTP 404)."
            409 -> "Conflict occurred (HTTP 409). Please try again."
            416 -> "Download range mismatch (HTTP 416). Restarting from beginning."
            429 -> "Rate limited by provider (HTTP 429). Retrying shortly."
            507 -> "Insufficient temporary server storage (HTTP 507). Please try again later."
            500, 502, 503 -> "Server is temporarily unavailable. Please try again in a moment."
            else -> "Request failed (HTTP $statusCode). Please try again."
        }
        return ParsedApiError(
            message = fallbackMessage,
            code = parsedCode,
            requestId = parsedRequestId,
            retryable = parsedRetryable
        )
    }

    fun parseError(errorBody: String?, statusCode: Int, isRegister: Boolean = false): String {
        return parseErrorEnvelope(errorBody, statusCode, isRegister).message
    }

    private fun redactUrlSecrets(message: String): String {
        return querySecretRegex.replace(message) { "${it.groupValues[1]}[REDACTED]" }
    }

    private val loggingInterceptor = HttpLoggingInterceptor { message ->
        Log.d("OkHttp", redactUrlSecrets(message))
    }.apply {
        level = if (BuildConfig.DEBUG) HttpLoggingInterceptor.Level.BASIC else HttpLoggingInterceptor.Level.NONE
        redactHeader("Authorization")
        redactHeader("Cookie")
    }

    fun setDebugLogging(enabled: Boolean) {
        loggingInterceptor.level = if (enabled || BuildConfig.DEBUG) {
            HttpLoggingInterceptor.Level.BASIC
        } else {
            HttpLoggingInterceptor.Level.NONE
        }
    }

    private fun createClient(cache: Cache? = null): OkHttpClient {
        val dispatcher = Dispatcher().apply {
            maxRequests = 64
            maxRequestsPerHost = 16
        }
        val builder = OkHttpClient.Builder()
            .dispatcher(dispatcher)
            .connectionPool(ConnectionPool(12, 5, TimeUnit.MINUTES))
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(300, TimeUnit.SECONDS)
            .writeTimeout(120, TimeUnit.SECONDS)
            .addInterceptor(RequestCorrelationInterceptor())
            .addInterceptor(authInterceptor)
            .addInterceptor(loggingInterceptor)
        if (cache != null) {
            builder.cache(cache)
        }
        return builder.build()
    }

    var okHttpClient: OkHttpClient = createClient()
        private set

    val moshi: Moshi = Moshi.Builder()
        .addLast(KotlinJsonAdapterFactory())
        .build()

    private var retrofit: Retrofit = Retrofit.Builder()
        .baseUrl(API_BASE_URL)
        .client(okHttpClient)
        .addConverterFactory(MoshiConverterFactory.create(moshi))
        .build()

    var apiService: CloudyteApi = retrofit.create(CloudyteApi::class.java)
        private set

    private fun openEncryptedPrefs(context: Context): SharedPreferences {
        val masterKey = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        return EncryptedSharedPreferences.create(
            context,
            SECURE_PREFS_NAME,
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
    }

    fun init(context: Context) {
        // Remove any legacy plaintext fallback token file so tokens never persist unencrypted on disk
        runCatching { context.deleteSharedPreferences("cloudyte_secure_fallback") }

        sharedPreferences = try {
            val secure = openEncryptedPrefs(context)
            val legacy = context.getSharedPreferences("cloudyte_prefs", Context.MODE_PRIVATE)
            if (secure.getString(TOKEN_KEY, null).isNullOrBlank()) {
                legacy.getString(TOKEN_KEY, null)?.let { secure.edit().putString(TOKEN_KEY, it).apply() }
            }
            legacy.edit().remove(TOKEN_KEY).apply()
            secure
        } catch (e: Exception) {
            Log.w("ApiClient", "Encrypted token storage corrupted; resetting encrypted preferences", e)
            runCatching { context.deleteSharedPreferences(SECURE_PREFS_NAME) }
            try {
                openEncryptedPrefs(context)
            } catch (retryErr: Exception) {
                Log.e("ApiClient", "Encrypted token storage unavailable; holding session token in memory only", retryErr)
                null
            }
        }
        inMemoryToken = sharedPreferences?.getString(TOKEN_KEY, null) ?: inMemoryToken
        val httpCache = Cache(File(context.cacheDir, "http"), 50L * 1024 * 1024)
        okHttpClient = createClient(httpCache)
        retrofit = Retrofit.Builder()
            .baseUrl(API_BASE_URL)
            .client(okHttpClient)
            .addConverterFactory(MoshiConverterFactory.create(moshi))
            .build()
        apiService = retrofit.create(CloudyteApi::class.java)
    }

    fun getThumbUrl(accountId: String, fileId: String, size: Int = 256): String {
        val account = Uri.encode(accountId)
        val file = Uri.encode(fileId)
        return "$API_BASE_URL/api/thumb/$account/$file?s=${size.coerceIn(64, 512)}"
    }

    fun getCleanDownloadUrl(accountId: String, fileId: String, preview: Boolean = false): String {
        val base = "$API_BASE_URL/api/download/${Uri.encode(accountId)}/${Uri.encode(fileId)}"
        return if (preview) "$base?preview=1" else base
    }

    fun getFileUrl(accountId: String, fileId: String, preview: Boolean = false): String {
        return getCleanDownloadUrl(accountId, fileId, preview)
    }

    /**
     * Resolves a 10-minute signed media URL (`GET /api/media-link/:accountId/:fileId`) bound to
     * the exact account and file, caching valid links in memory and falling back to
     * [getCleanDownloadUrl] if offline or unsupported.
     */
    suspend fun resolveSignedMediaUrl(
        accountId: String,
        fileId: String,
        preview: Boolean = true,
        api: CloudyteApi = apiService,
        nowMs: Long = System.currentTimeMillis()
    ): String {
        if (accountId.isBlank() || fileId.isBlank()) {
            return getCleanDownloadUrl(accountId, fileId, preview)
        }
        val key = "$accountId:$fileId"
        val cached = signedMediaCache[key]
        if (cached != null && cached.expiresAtMs > nowMs + 30_000L) {
            return cached.url
        }
        val fetched = runCatching {
            val response = api.getMediaLink(accountId, fileId)
            val body = response.body()
            if (response.isSuccessful && body != null && body.url.isNotBlank()) {
                val resolvedUrl = when {
                    body.url.startsWith("http://", ignoreCase = true) ||
                        body.url.startsWith("https://", ignoreCase = true) -> body.url
                    body.url.startsWith("/") -> "$API_BASE_URL${body.url}"
                    else -> "$API_BASE_URL/${body.url}"
                }
                val expiry = if (body.expiresAt > nowMs) body.expiresAt else (nowMs + 9 * 60 * 1000L)
                signedMediaCache[key] = CachedMediaLink(resolvedUrl, expiry)
                resolvedUrl
            } else {
                null
            }
        }.getOrNull()
        return fetched ?: getCleanDownloadUrl(accountId, fileId, preview)
    }
}
