package dev.santo

import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.testApplication
import kotlin.test.*

class ServerTest {

    @Test
    fun `test root endpoint`() = testApplication {
        application {
            rootModule()
        }
        // verify server root returns 200
        assertEquals(HttpStatusCode.OK, client.get("/").status)
    }

    @Test
    fun `test ready endpoint returns 200`() = testApplication {
        application {
            rootModule()
        }
        val response = client.get("/ready")
        assertEquals(HttpStatusCode.OK, response.status)
        // health check must carry an empty body
        assertEquals("", response.bodyAsText())
        assertEquals("0", response.headers[HttpHeaders.ContentLength])
    }

}
