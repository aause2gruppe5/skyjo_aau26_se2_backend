package at.aau.se2.skyjo.controller

import at.aau.se2.skyjo.model.auth.AuthUserDto
import at.aau.se2.skyjo.model.auth.ErrorResponse
import at.aau.se2.skyjo.model.lobby.LobbyPlayer
import at.aau.se2.skyjo.model.lobby.LobbyState
import at.aau.se2.skyjo.model.lobby.LobbyStatus
import at.aau.se2.skyjo.model.lobby.LobbySummaryResponse
import at.aau.se2.skyjo.service.AuthService
import at.aau.se2.skyjo.service.GameService
import at.aau.se2.skyjo.service.LobbyService
import at.aau.se2.skyjo.service.UnauthorizedException
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.mock
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import org.springframework.http.HttpStatus
import org.springframework.messaging.simp.SimpMessageSendingOperations

class LobbyRestControllerTest {

    private val lobbyService: LobbyService = mock()
    private val gameService: GameService = mock()
    private val messagingTemplate: SimpMessageSendingOperations = mock()
    private val authService: AuthService = mock()
    private val authSupport = AuthSupport(authService)
    private val controller = LobbyController(lobbyService, gameService, messagingTemplate, null, authSupport)

    @Test
    fun `createLobby creates lobby for authenticated user`() {
        whenever(authService.requireUser("token")).thenReturn(user())
        whenever(lobbyService.createLobby(user())).thenReturn(lobby())

        val result = controller.createLobby("Bearer token")

        assertEquals(HttpStatus.CREATED, result.statusCode)
        assertEquals("ABC123", (result.body as LobbySummaryResponse).joinCode)
    }

    @Test
    fun `joinLobbyByCode joins authenticated user`() {
        whenever(authService.requireUser("token")).thenReturn(user("user-2", "Bob"))
        whenever(lobbyService.joinLobby(user("user-2", "Bob"), "ABC123")).thenReturn(
            lobby(players = listOf("Alice", "Bob")),
        )

        val result = controller.joinLobbyByCode("ABC123", "Bearer token")

        assertEquals(HttpStatus.OK, result.statusCode)
        assertEquals(listOf("Alice", "Bob"), (result.body as LobbySummaryResponse).players.map { it.nickname })
    }

    @Test
    fun `leaveLobbyById leaves authenticated lobby and broadcasts update`() {
        whenever(authService.requireUser("token")).thenReturn(user())
        whenever(lobbyService.leaveLobby("user-1", "lobby-1")).thenReturn(lobby(players = listOf("Bob")))

        val result = controller.leaveLobbyById("lobby-1", "Bearer token")

        assertEquals(HttpStatus.OK, result.statusCode)
        assertEquals(listOf("Bob"), (result.body as LobbySummaryResponse).players.map { it.nickname })
        verify(messagingTemplate).convertAndSend(any<String>(), any<Any>())
    }

    @Test
    fun `joinLobbyByCode returns not found for invalid code`() {
        whenever(authService.requireUser("token")).thenReturn(user())
        whenever(lobbyService.joinLobby(any(), any())).thenThrow(IllegalStateException("lobby not found"))

        val result = controller.joinLobbyByCode("BAD999", "Bearer token")

        assertEquals(HttpStatus.NOT_FOUND, result.statusCode)
        assertEquals("lobby not found", (result.body as ErrorResponse).message)
    }

    @Test
    fun `createLobby returns bad request for invalid lobby operation`() {
        whenever(authService.requireUser("token")).thenReturn(user())
        whenever(lobbyService.createLobby(user())).thenThrow(IllegalStateException("user is already in a lobby"))

        val result = controller.createLobby("Bearer token")

        assertEquals(HttpStatus.BAD_REQUEST, result.statusCode)
        assertEquals("user is already in a lobby", (result.body as ErrorResponse).message)
    }

    @Test
    fun `currentLobby returns active lobby when one exists`() {
        whenever(authService.requireUser("token")).thenReturn(user())
        whenever(lobbyService.getCurrentLobbyForUser("user-1")).thenReturn(lobby())

        val result = controller.currentLobby("Bearer token")

        assertEquals(HttpStatus.OK, result.statusCode)
        assertEquals("ABC123", (result.body as LobbySummaryResponse).joinCode)
    }

    @Test
    fun `currentLobby returns no content when user has no lobby`() {
        whenever(authService.requireUser("token")).thenReturn(user())
        whenever(lobbyService.getCurrentLobbyForUser("user-1")).thenReturn(null)

        val result = controller.currentLobby("Bearer token")

        assertEquals(HttpStatus.NO_CONTENT, result.statusCode)
    }

    @Test
    fun `lobby endpoints return unauthorized when bearer token is invalid`() {
        whenever(authService.requireUser("bad-token")).thenThrow(UnauthorizedException())

        val result = controller.createLobby("Bearer bad-token")

        assertEquals(HttpStatus.UNAUTHORIZED, result.statusCode)
        assertEquals("Authentication required", (result.body as ErrorResponse).message)
    }

    private fun user(userId: String = "user-1", username: String = "Alice") =
        AuthUserDto(userId = userId, username = username)

    private fun lobby(players: List<String> = listOf("Alice")) = LobbyState(
        lobbyId = "lobby-1",
        joinCode = "ABC123",
        players = players.mapIndexed { index, name ->
            LobbyPlayer(
                sessionId = "user-${index + 1}",
                nickname = name,
                isHost = index == 0,
                userId = "user-${index + 1}",
            )
        },
        status = LobbyStatus.WAITING,
        maxPlayers = 6,
    )
}
