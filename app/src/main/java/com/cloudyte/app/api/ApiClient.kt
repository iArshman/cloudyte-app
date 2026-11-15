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

    fun saveToken(token: String?) {
        inMemoryToken = token
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

    fun parseError(errorBody: String?, statusCode: Int, isRegister: Boolean = false): String {
        if (!errorBody.isNullOrBlank()) {
            try {
                val json = org.json.JSONObject(errorBody)
                val nestedErrObj = json.optJSONObject("error")
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
                    return err
                }
            } catch (_: Exception) {
                if (errorBody.length < 150 && !errorBody.contains("<html", ignoreCase = true) && !errorBody.trimStart().startsWith("{")) {
                    return errorBody.trim()
                }
            }
        }
        return when (statusCode) {
            401 -> if (isRegister) "Registration unauthorized. Please try a different username." else "Session expired or unauthorized. Please sign in again."
            400 -> "Invalid request (HTTP 400). Please check and try again."
            403 -> "Access denied by cloud provider (HTTP 403)."
            404 -> "File or resource not found (HTTP 404)."
            409 -> "Conflict occurred (HTTP 409). Please try again."
            416 -> "Download range mismatch (HTTP 416). Restarting from beginning."
            429 -> "Rate limited by provider (HTTP 429). Retrying shortly."
            500, 502, 503 -> "Server is temporarily unavailable. Please try again in a moment."
            else -> "Request failed (HTTP $statusCode). Please try again."
        }
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
}
