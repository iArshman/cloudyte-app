package com.example.api

import android.content.Context
import android.content.SharedPreferences
import com.example.BuildConfig
import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import okhttp3.Cache
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.converter.moshi.MoshiConverterFactory
import java.io.File
import java.util.concurrent.TimeUnit

object ApiClient {
    // Configurable API URL
    const val API_BASE_URL = "https://drive.arshman.me"

    private lateinit var sharedPreferences: SharedPreferences

    fun saveToken(token: String?) {
        if (::sharedPreferences.isInitialized) {
            sharedPreferences.edit().putString("auth_token", token).apply()
        }
    }

    fun getToken(): String? {
        return if (::sharedPreferences.isInitialized) {
            sharedPreferences.getString("auth_token", null)
        } else {
            null
        }
    }

    private val authInterceptor = Interceptor { chain ->
        val request = chain.request()
        val path = request.url.encodedPath
        val requestBuilder = request.newBuilder()
        val token = getToken()
        if (!token.isNullOrEmpty() && !path.endsWith("/login") && !path.endsWith("/register")) {
            requestBuilder.addHeader("Authorization", "Bearer $token")
        }
        chain.proceed(requestBuilder.build())
    }

    fun parseError(errorBody: String?, statusCode: Int, isRegister: Boolean = false): String {
        if (!errorBody.isNullOrBlank()) {
            try {
                val json = org.json.JSONObject(errorBody)
                val err = json.optString("error").takeIf { it.isNotBlank() }
                    ?: json.optString("message").takeIf { it.isNotBlank() }
                    ?: json.optString("msg").takeIf { it.isNotBlank() }
                if (!err.isNullOrBlank()) {
                    return err
                }
            } catch (_: Exception) {
                if (errorBody.length < 150 && !errorBody.contains("<html", ignoreCase = true)) {
                    return errorBody.trim()
                }
            }
        }
        return when (statusCode) {
            401 -> if (isRegister) "Registration unauthorized. Please try a different username." else "Invalid username or password. If you don't have an account, please Register."
            400 -> "Invalid username or password format. Please check and try again."
            404 -> "Account not found. Please click 'Register' below to create an account."
            409 -> "Username is already taken. Please choose another username or log in."
            500, 502, 503 -> "Server is temporarily unavailable. Please try again in a moment."
            else -> "Authentication failed (Error $statusCode). Please try again."
        }
    }

    private val loggingInterceptor = HttpLoggingInterceptor().apply {
        level = if (BuildConfig.DEBUG) HttpLoggingInterceptor.Level.BASIC else HttpLoggingInterceptor.Level.NONE
        redactHeader("Authorization")
    }

    fun setDebugLogging(enabled: Boolean) {
        loggingInterceptor.level = if (enabled || BuildConfig.DEBUG) {
            HttpLoggingInterceptor.Level.BASIC
        } else {
            HttpLoggingInterceptor.Level.NONE
        }
    }

    private fun createClient(cache: Cache? = null): OkHttpClient {
        val builder = OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
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

    fun init(context: Context) {
        sharedPreferences = context.getSharedPreferences("cloudyte_prefs", Context.MODE_PRIVATE)
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
        return "$API_BASE_URL/api/thumb/$accountId/$fileId?s=$size"
    }

    fun getCleanDownloadUrl(accountId: String, fileId: String, preview: Boolean = false): String {
        val base = "$API_BASE_URL/api/download/$accountId/$fileId"
        return if (preview) "$base?preview=1" else base
    }

    fun getFileUrl(accountId: String, fileId: String, preview: Boolean = false): String {
        val token = getToken()
        val base = "$API_BASE_URL/api/download/$accountId/$fileId"
        return when {
            preview && !token.isNullOrEmpty() -> "$base?preview=1&token=$token"
            preview -> "$base?preview=1"
            !token.isNullOrEmpty() -> "$base?token=$token"
            else -> base
        }
    }
}
