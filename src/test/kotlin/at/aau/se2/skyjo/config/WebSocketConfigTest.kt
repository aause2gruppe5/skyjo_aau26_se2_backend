package at.aau.se2.skyjo.config

import at.aau.se2.skyjo.model.auth.AuthPrincipal
import at.aau.se2.skyjo.model.auth.AuthUserDto
import at.aau.se2.skyjo.service.AuthService
import at.aau.se2.skyjo.service.UnauthorizedException
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import org.springframework.http.server.ServerHttpRequest
import java.net.URI

class WebSocketConfigTest {

    private val authService: AuthService = mock()
    private val handler = AuthPrincipalHandshakeHandler(authService)

    @Test
    fun `valid websocket ticket creates authenticated principal`() {
        whenever(authService.consumeWebSocketTicket("ticket-123"))
            .thenReturn(AuthUserDto(userId = "user-1", username = "Alice"))

        val principal = handler.determinePrincipal(
            requestWithUri("http://localhost/ws?ticket=ticket-123"),
        ) as AuthPrincipal

        assertEquals("user-1", principal.name)
        assertEquals("Alice", principal.username)
    }

    @Test
    fun `missing websocket ticket is rejected`() {
        assertThrows<UnauthorizedException> {
            handler.determinePrincipal(
                requestWithUri("http://localhost/ws"),
            )
        }
    }

    @Test
    fun `invalid websocket ticket is rejected`() {
        whenever(authService.consumeWebSocketTicket("bad-ticket")).thenReturn(null)

        assertThrows<UnauthorizedException> {
            handler.determinePrincipal(
                requestWithUri("http://localhost/ws?ticket=bad-ticket"),
            )
        }
    }

    @Test
    fun `url encoded websocket ticket is decoded before validation`() {
        whenever(authService.consumeWebSocketTicket("ticket/value"))
            .thenReturn(AuthUserDto(userId = "user-1", username = "Alice"))

        val principal = handler.determinePrincipal(
            requestWithUri("http://localhost/ws?ticket=ticket%2Fvalue"),
        )

        assertEquals("user-1", principal.name)
    }

    private fun requestWithUri(uri: String): ServerHttpRequest {
        val request = mock<ServerHttpRequest>()
        whenever(request.uri).thenReturn(URI(uri))
        return request
    }
}
