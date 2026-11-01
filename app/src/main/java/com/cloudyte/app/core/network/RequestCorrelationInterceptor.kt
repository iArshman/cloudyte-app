package com.cloudyte.app.core.network

import com.cloudyte.app.core.common.CorrelationId
import okhttp3.Interceptor
import okhttp3.Response

class RequestCorrelationInterceptor(
    private val onCorrelation: (String) -> Unit = {}
) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val correlationId = CorrelationId.newId()
        onCorrelation(correlationId)
        val request = chain.request().newBuilder()
            .header("X-Correlation-Id", correlationId)
            .build()
        return chain.proceed(request)
    }
}
