package com.cloudyte.app.data.providers

import com.cloudyte.app.api.CloudyteApi
import java.util.Locale

/** Resolves an adapter only from the selected account's declared provider identity. */
class ProviderRegistry(api: CloudyteApi) {
    private val providers: Map<String, CloudProvider> = listOf(
        TelegramProvider(api), GoogleDriveProvider(api), OneDriveProvider(api), CloudyteProvider(api)
    ).associateBy { it.type }

    fun forAccountType(accountType: String?): CloudProvider {
        val normalized = normalize(accountType)
        return providers[normalized]
            ?: throw UnsupportedProviderOperation(normalized.ifBlank { "unknown" }, "account provider resolution")
    }

    fun byProviderType(providerType: String?): CloudProvider = forAccountType(providerType)

    companion object {
        fun normalize(value: String?): String {
            val normalized = value.orEmpty().trim().lowercase(Locale.ROOT).replace('-', '_').replace(' ', '_')
            return when (normalized) {
                "tg", "tgdrive", "tg_drive", "telegram_drive", "telegram" -> "telegram"
                "google", "gdrive", "google_drive", "googledrive" -> "google_drive"
                "onedrive", "one_drive", "microsoft_onedrive" -> "onedrive"
                "cloudyte", "cloudyte_drive" -> "cloudyte"
                else -> normalized
            }
        }
    }
}
