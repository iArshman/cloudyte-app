package com.cloudyte.app.core.common

import java.util.UUID

object CorrelationId {
    fun newId(): String = UUID.randomUUID().toString()
}
