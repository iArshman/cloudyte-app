package com.example.api

import android.content.Context
import android.content.Intent
import android.net.Uri

object DownloadHelper {
    fun downloadFile(context: Context, url: String, fileName: String, token: String? = null) {
        val uriBuilder = Uri.parse(url).buildUpon()
        if (token != null) {
            // Some backends support token in query param
            uriBuilder.appendQueryParameter("token", token)
        }
        val intent = Intent(Intent.ACTION_VIEW, uriBuilder.build())
        context.startActivity(intent)
    }
}
