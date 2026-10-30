package com.cloudyte.app.core.common

sealed interface AppError {
    val message: String

    data class Network(override val message: String, val cause: Throwable? = null) : AppError
    data class Unauthorized(override val message: String = "Authentication is required") : AppError
    data class NotFound(override val message: String = "The requested item was not found") : AppError
    data class Validation(override val message: String) : AppError
    data class Storage(override val message: String, val cause: Throwable? = null) : AppError
    data class Unknown(override val message: String, val cause: Throwable? = null) : AppError

    companion object {
        fun from(throwable: Throwable): AppError = when (throwable) {
            is java.io.IOException -> Network(throwable.message ?: "Network request failed", throwable)
            is SecurityException -> Unauthorized(throwable.message ?: "Authentication is required")
            else -> Unknown(throwable.message ?: "Something went wrong", throwable)
        }
    }
}
