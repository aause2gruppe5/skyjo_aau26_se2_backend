package at.aau.se2.skyjo.config

import at.aau.se2.skyjo.model.auth.AuthPrincipal
import at.aau.se2.skyjo.model.auth.AuthUserDto
import at.aau.se2.skyjo.service.AuthService
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.mockito.kotlin.mock
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import org.springframework.http.HttpStatus
import org.springframework.http.server.ServerHttpRequest
import org.springframework.http.server.ServerHttpResponse
import org.springframework.web.socket.WebSocketHandler
import java.net.URI

class WebSocketConfigTest {

    private val authService: AuthService = mock()
    private val interceptor = TicketHandshakeInterceptor(authService)
    private val handler = AuthPrincipalHandshakeHandler()

    @Test
    fun `valid ticket sets principal in attributes and returns true`() {
        whenever(authService.consumeWebSocketTicket("ticket-123"))
            .thenReturn(AuthUserDto(userId = "user-1", username = "Alice"))

        val attrs = mutableMapOf<String, Any>()
        val result = interceptor.beforeHandshake(
            requestWithUri("http://localhost/ws?ticket=ticket-123"),
            mockResponse(),
            mock(),
            attrs,
        )

        assertTrue(result)
        val principal = attrs[TicketHandshakeInterceptor.ATTR_PRINCIPAL] as AuthPrincipal
        assertEquals("user-1", principal.name)
        assertEquals("Alice", principal.username)
    }

    @Test
    fun `missing ticket returns 401 and false`() {
        val response = mockResponse()

        val result = interceptor.beforeHandshake(
            requestWithUri("http://localhost/ws"),
            response,
            mock(),
            mutableMapOf(),
        )

        assertFalse(result)
        verify(response).setStatusCode(HttpStatus.UNAUTHORIZED)
    }

    @Test
    fun `invalid ticket returns 401 and false`() {
        whenever(authService.consumeWebSocketTicket("bad-ticket")).thenReturn(null)
        val response = mockResponse()

        val result = interceptor.beforeHandshake(
            requestWithUri("http://localhost/ws?ticket=bad-ticket"),
            response,
            mock(),
            mutableMapOf(),
        )

        assertFalse(result)
        verify(response).setStatusCode(HttpStatus.UNAUTHORIZED)
    }

    @Test
    fun `url encoded ticket is decoded before validation`() {
        whenever(authService.consumeWebSocketTicket("ticket/value"))
            .thenReturn(AuthUserDto(userId = "user-1", username = "Alice"))

        val attrs = mutableMapOf<String, Any>()
        val result = interceptor.beforeHandshake(
            requestWithUri("http://localhost/ws?ticket=ticket%2Fvalue"),
            mockResponse(),
            mock(),
            attrs,
        )

        assertTrue(result)
        assertNotNull(attrs[TicketHandshakeInterceptor.ATTR_PRINCIPAL])
    }

    @Test
    fun `determineUser reads principal from attributes`() {
        val principal = AuthPrincipal("user-1", "Alice")
        val attrs = mapOf<String, Any>(TicketHandshakeInterceptor.ATTR_PRINCIPAL to principal)

        val result = handler.determineUser(requestWithUri("http://localhost/ws"), mock<WebSocketHandler>(), attrs)

        assertEquals(principal, result)
    }

    @Test
    fun `determineUser returns null when principal is absent from attributes`() {
        val result = handler.determineUser(requestWithUri("http://localhost/ws"), mock<WebSocketHandler>(), emptyMap<String, Any>())

        assertNull(result)
    }

    private fun requestWithUri(uri: String): ServerHttpRequest {
        val request = mock<ServerHttpRequest>()
        whenever(request.uri).thenReturn(URI(uri))
        return request
    }

    private fun mockResponse(): ServerHttpResponse = mock()
}
