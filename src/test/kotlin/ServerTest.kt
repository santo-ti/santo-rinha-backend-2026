package dev.santo

import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.testApplication
import kotlin.test.Test
import kotlin.test.assertEquals

class ServerTest {

    @Test
    fun `ready returns 503 while index is not loaded`() = testApplication {
        application { rootModule(testComponents()) }
        assertEquals(HttpStatusCode.ServiceUnavailable, client.get("/ready").status)
    }

    @Test
    fun `ready returns 200 with empty body once index is published`() = testApplication {
        application { rootModule(testComponents(FixedFraudIndex(0))) }
        val response = client.get("/ready")
        assertEquals(HttpStatusCode.OK, response.status)
        // readiness probe must carry an empty body
        assertEquals("", response.bodyAsText())
        assertEquals("0", response.headers[HttpHeaders.ContentLength])
    }
}
