package com.vela.android.lab.data.paper.submit

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Test

class AlpacaPaperOrderSubmitHttpClientTest {
    @Test
    fun `default client disables redirects and automatic connection retry`() {
        val client = OkHttpAlpacaPaperOrderSubmitHttpClient.defaultClient()

        assertFalse(client.followRedirects)
        assertFalse(client.followSslRedirects)
        assertFalse(client.retryOnConnectionFailure)
    }
}
