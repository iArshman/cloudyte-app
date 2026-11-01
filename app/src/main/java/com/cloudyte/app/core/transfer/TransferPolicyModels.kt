package com.cloudyte.app.core.transfer

enum class NetworkStatus { UNAVAILABLE, VALIDATED_METERED, VALIDATED_UNMETERED, CAPTIVE_PORTAL }

data class NetworkPolicySnapshot(
    val status: NetworkStatus,
    val allowMeteredTransfers: Boolean,
    val allowRoaming: Boolean,
    val requireChargingForBackup: Boolean,
    val batterySaverActive: Boolean
)

sealed interface TransferError {
    val code: String
    val retryable: Boolean

    data class Network(override val code: String = "NETWORK_UNAVAILABLE") : TransferError { override val retryable = true }
    data class RateLimited(val retryAfterSeconds: Long? = null, override val code: String = "RATE_LIMITED") : TransferError { override val retryable = true }
    data class Server(override val code: String = "SERVER_ERROR") : TransferError { override val retryable = true }
    data class Authentication(override val code: String = "AUTH_REQUIRED") : TransferError { override val retryable = false }
    data class Permission(override val code: String = "PERMISSION_DENIED") : TransferError { override val retryable = false }
    data class InvalidRequest(override val code: String = "INVALID_REQUEST") : TransferError { override val retryable = false }
    data class Storage(override val code: String = "STORAGE_UNAVAILABLE") : TransferError { override val retryable = false }
    data class Unknown(override val code: String = "UNKNOWN") : TransferError { override val retryable = false }
}

object TransferErrorMapper {
    fun fromHttp(status: Int, retryAfterSeconds: Long? = null): TransferError = when {
        status == 401 -> TransferError.Authentication()
        status == 403 -> TransferError.Permission()
        status == 408 || status == 425 -> TransferError.Network("HTTP_$status")
        status == 429 -> TransferError.RateLimited(retryAfterSeconds)
        status in 500..599 -> TransferError.Server("HTTP_$status")
        status in 400..499 -> TransferError.InvalidRequest("HTTP_$status")
        else -> TransferError.Unknown("HTTP_$status")
    }

    fun fromNetworkFailure(): TransferError = TransferError.Network()
}
