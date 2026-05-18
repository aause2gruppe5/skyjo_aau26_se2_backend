package at.aau.se2.skyjo.controller

import at.aau.se2.skyjo.model.auth.AuthResponse
import at.aau.se2.skyjo.model.auth.AuthUserDto
import at.aau.se2.skyjo.model.auth.ErrorResponse
import at.aau.se2.skyjo.model.auth.LoginRequest
import at.aau.se2.skyjo.model.auth.RegisterRequest
import at.aau.se2.skyjo.model.auth.WsTicketResponse
import at.aau.se2.skyjo.service.AuthService
import at.aau.se2.skyjo.service.DuplicateUsernameException
import at.aau.se2.skyjo.service.InvalidCredentialsException
import at.aau.se2.skyjo.service.UnauthorizedException
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.mock
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import org.springframework.http.HttpStatus

class AuthControllerTest {

    private val authService: AuthService = mock()
    private val authSupport = AuthSupport(authService)
    private val controller = AuthController(authService, authSupport)

    @Test
    fun `register returns created auth response`() {
        val response = authResponse()
        whenever(authService.register("Alice", "password123")).thenReturn(response)

        val result = controller.register(RegisterRequest("Alice", "password123"))

        assertEquals(HttpStatus.CREATED, result.statusCode)
        assertEquals(response, result.body)
    }

    @Test
    fun `duplicate register returns conflict`() {
        whenever(authService.register(any(), any())).thenThrow(DuplicateUsernameException())

        val result = controller.register(RegisterRequest("Alice", "password123"))

        assertEquals(HttpStatus.CONFLICT, result.statusCode)
        assertEquals("Username is already taken", (result.body as ErrorResponse).message)
    }

    @Test
    fun `login returns auth response`() {
        val response = authResponse()
        whenever(authService.login("Alice", "password123")).thenReturn(response)

        val result = controller.login(LoginRequest("Alice", "password123"))

        assertEquals(HttpStatus.OK, result.statusCode)
        assertEquals(response, result.body)
    }

    @Test
    fun `login returns generic unauthorized on invalid credentials`() {
        whenever(authService.login(any(), any())).thenThrow(InvalidCredentialsException())

        val result = controller.login(LoginRequest("Alice", "bad-password"))

        assertEquals(HttpStatus.UNAUTHORIZED, result.statusCode)
        assertEquals("Invalid username or password", (result.body as ErrorResponse).message)
    }

    @Test
    fun `me resolves bearer token`() {
        whenever(authService.requireUser("token")).thenReturn(AuthUserDto("user-1", "Alice"))

        val result = controller.me("Bearer token")

        assertEquals(HttpStatus.OK, result.statusCode)
        assertEquals("Alice", (result.body as AuthUserDto).username)
    }

    @Test
    fun `me returns unauthorized when bearer token is missing`() {
        val result = controller.me(null)

        assertEquals(HttpStatus.UNAUTHORIZED, result.statusCode)
        assertEquals("Authentication required", (result.body as ErrorResponse).message)
    }

    @Test
    fun `logout revokes bearer token`() {
        val result = controller.logout("Bearer token")

        assertEquals(HttpStatus.NO_CONTENT, result.statusCode)
        verify(authService).logout("token")
    }

    @Test
    fun `ws ticket requires valid bearer token`() {
        whenever(authService.createWebSocketTicket("token")).thenReturn(WsTicketResponse("ticket", 2_000L))

        val result = controller.createWebSocketTicket("Bearer token")

        assertEquals(HttpStatus.OK, result.statusCode)
        assertEquals("ticket", (result.body as WsTicketResponse).ticket)
    }

    @Test
    fun `ws ticket returns unauthorized for invalid token`() {
        whenever(authService.createWebSocketTicket("token")).thenThrow(UnauthorizedException())

        val result = controller.createWebSocketTicket("Bearer token")

        assertEquals(HttpStatus.UNAUTHORIZED, result.statusCode)
        assertEquals("Authentication required", (result.body as ErrorResponse).message)
    }

    private fun authResponse() = AuthResponse(
        token = "token",
        user = AuthUserDto(userId = "user-1", username = "Alice"),
    )
}
