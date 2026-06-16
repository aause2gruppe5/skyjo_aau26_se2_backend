package at.aau.se2.skyjo.event

import io.mockk.*
import io.mockk.impl.annotations.MockK
import io.mockk.impl.annotations.RelaxedMockK
import io.mockk.junit5.MockKExtension
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.springframework.messaging.simp.SimpMessageHeaderAccessor
import org.springframework.messaging.simp.SimpMessageSendingOperations
import org.springframework.messaging.support.MessageBuilder
import org.springframework.web.socket.messaging.SessionConnectedEvent
import org.springframework.web.socket.messaging.SessionDisconnectEvent
import java.security.Principal
import at.aau.se2.skyjo.service.*
import at.aau.se2.skyjo.model.lobby.*
import at.aau.se2.skyjo.model.*

@ExtendWith(MockKExtension::class)
class WebSocketEventListenerTest {

    @RelaxedMockK
    lateinit var messagingTemplate: SimpMessageSendingOperations

    @MockK
    lateinit var lobbyService: LobbyService

    @RelaxedMockK
    lateinit var authService: AuthService

    @MockK
    lateinit var gameService: GameService

    private lateinit var listener: WebSocketEventListener

    @MockK
    lateinit var principal: Principal

    @BeforeEach
    fun setUp() {
        listener = WebSocketEventListener(messagingTemplate, lobbyService, gameService = null, authService = authService)
    }

    private val playerId = "player-123"

    @Nested
    inner class HandleWebSocketConnectListenerTests {

        @Test
        fun `loggt neue Verbindung, wenn User vorhanden ist`() {
            val event = mockk<SessionConnectedEvent>()
            every { event.user } returns principal
            every { principal.name } returns playerId
            every { lobbyService.getCurrentLobbyForUser(playerId) } returns null

            // Ausführung (Wir testen hier primär, dass keine Exception fliegt,
            // da die Methode nur loggt und keinen State ändert)
            listener.handleWebSocketConnectListener(event)

            verify { event.user }
            verify { authService.markUserConnected(playerId, null) }
        }

        @Test
        fun `markiert Verbindung mit aktueller Lobby wenn User in Join-Code Lobby ist`() {
            val event = mockk<SessionConnectedEvent>()
            every { event.user } returns principal
            every { principal.name } returns playerId
            every { lobbyService.getCurrentLobbyForUser(playerId) } returns LobbyState(
                lobbyId = "lobby-1",
                joinCode = "ABC123",
            )

            listener.handleWebSocketConnectListener(event)

            verify { authService.markUserConnected(playerId, "lobby-1") }
        }

        @Test
        fun `refreshes active websocket presence periodically`() {
            val event = mockk<SessionConnectedEvent>()
            every { event.user } returns principal
            every { principal.name } returns playerId
            every { lobbyService.getCurrentLobbyForUser(playerId) } returns null
            listener.handleWebSocketConnectListener(event)
            clearMocks(authService)
            every { lobbyService.getCurrentLobbyForUser(playerId) } returns LobbyState(
                lobbyId = "lobby-2",
                joinCode = "XYZ789",
            )

            listener.refreshActiveWebSocketPresence()

            verify { authService.markUserConnected(playerId, "lobby-2") }
        }

        @Test
        fun `does not refresh disconnected websocket sessions`() {
            val connectEvent = mockk<SessionConnectedEvent>()
            val disconnectEvent = mockk<SessionDisconnectEvent>()
            every { connectEvent.user } returns principal
            every { disconnectEvent.user } returns principal
            every { principal.name } returns playerId
            every { lobbyService.getCurrentLobbyForUser(playerId) } returns null
            every { lobbyService.isPlayerInLobby(playerId) } returns false
            listener.handleWebSocketConnectListener(connectEvent)
            listener.handleWebSocketDisconnectListener(disconnectEvent)
            clearMocks(authService)

            listener.refreshActiveWebSocketPresence()

            verify(exactly = 0) { authService.markUserConnected(any(), any()) }
        }

        @Test
        fun `duplicate disconnect for one websocket session does not clear another active session`() {
            every { principal.name } returns playerId
            every { lobbyService.getCurrentLobbyForUser(playerId) } returns null
            every { lobbyService.isPlayerInLobby(playerId) } returns false
            listener.handleWebSocketConnectListener(connectedEvent("session-1"))
            listener.handleWebSocketConnectListener(connectedEvent("session-2"))
            clearMocks(authService)

            listener.handleWebSocketDisconnectListener(disconnectEvent("session-1"))
            listener.handleWebSocketDisconnectListener(disconnectEvent("session-1"))

            verify(exactly = 0) { authService.markUserDisconnected(playerId) }

            listener.handleWebSocketDisconnectListener(disconnectEvent("session-2"))

            verify(exactly = 1) { authService.markUserDisconnected(playerId) }
        }

        @Test
        fun `disconnect for one of multiple websocket sessions does not leave game or lobby`() {
            val listenerWithGame = WebSocketEventListener(messagingTemplate, lobbyService, gameService, authService)
            every { principal.name } returns playerId
            every { lobbyService.getCurrentLobbyForUser(playerId) } returns null
            listenerWithGame.handleWebSocketConnectListener(connectedEvent("session-1"))
            listenerWithGame.handleWebSocketConnectListener(connectedEvent("session-2"))
            clearMocks(authService, gameService, lobbyService, messagingTemplate)

            listenerWithGame.handleWebSocketDisconnectListener(disconnectEvent("session-1"))

            verify(exactly = 0) { authService.markUserDisconnected(any()) }
            verify { gameService wasNot Called }
            verify { lobbyService wasNot Called }
            verify { messagingTemplate wasNot Called }
        }

        @Test
        fun `wirft keinen Fehler, wenn User null ist`() {
            val event = mockk<SessionConnectedEvent>()
            every { event.user } returns null

            listener.handleWebSocketConnectListener(event)

            verify { event.user }
        }
    }

    @Nested
    inner class HandleWebSocketDisconnectListenerTests {

        @Test
        fun `bricht ab, wenn User null ist`() {
            val event = mockk<SessionDisconnectEvent>()
            every { event.user } returns null

            listener.handleWebSocketDisconnectListener(event)

            verify { lobbyService wasNot Called }
            verify { messagingTemplate wasNot Called }
        }

        @Test
        fun `macht nichts, wenn Spieler nicht in der Lobby ist`() {
            val event = mockk<SessionDisconnectEvent>()
            every { event.user } returns principal
            every { principal.name } returns playerId

            // Spieler ist nicht in der Lobby
            every { lobbyService.isPlayerInLobby(playerId) } returns false
            every { lobbyService.getCurrentLobbyForUser(playerId) } returns null

            listener.handleWebSocketDisconnectListener(event)

            verify { authService.markUserDisconnected(playerId) }
            verify { lobbyService.isPlayerInLobby(playerId) }
            verify(exactly = 0) { lobbyService.leave(any()) }
            verify(exactly = 0) { lobbyService.leaveLobby(any(), any()) }
            verify { messagingTemplate wasNot Called }
        }

        @Test
        fun `sendet Disconnect Update fuer das tatsaechliche Spiel des Spielers`() {
            val event = mockk<SessionDisconnectEvent>()
            val listenerWithGame = WebSocketEventListener(messagingTemplate, lobbyService, gameService, authService)
            val gameState = GameUpdateMessage(
                phase = at.aau.se2.skyjo.game.model.GamePhase.AWAITING_DRAW,
                currentPlayerId = playerId,
                players = emptyList(),
                discardTopCard = null,
                drawnCard = null,
                roundResult = null,
                roundNumber = 1,
                totalScores = emptyList(),
                gameOver = false,
                gameId = "game-1",
                lobbyId = "lobby-1",
                disconnectedPlayers = listOf("Alice"),
            )
            every { event.user } returns principal
            every { principal.name } returns playerId
            every { gameService.markPlayerDisconnected(playerId) } returns gameState
            every { lobbyService.isPlayerInLobby(playerId) } returns false
            every { lobbyService.getCurrentLobbyForUser(playerId) } returns null

            listenerWithGame.handleWebSocketDisconnectListener(event)

            verify { gameService.markPlayerDisconnected(playerId) }
            verify(exactly = 0) { gameService.getCurrentState() }
            verify { messagingTemplate.convertAndSend("/topic/games/game-1", gameState) }
        }

        @Test
        fun `entfernt Spieler und sendet Update, wenn Spieler in der Lobby ist`() {
            val event = mockk<SessionDisconnectEvent>()
            every { event.user } returns principal
            every { principal.name } returns playerId

            // Spieler IST in der Lobby
            every { lobbyService.isPlayerInLobby(playerId) } returns true

            // Mock für den aktualisierten State nach dem Verlassen
            val updatedState = mockk<LobbyState> {
                every { lobbyId } returns null
                every { joinCode } returns null
                every { players } returns emptyList()
                every { status } returns mockk() // Nimmt dein LobbyStatus Enum
                every { maxPlayers } returns 4
            }
            every { lobbyService.leave(playerId) } returns updatedState
            every { lobbyService.getCurrentLobbyForUser(playerId) } returns null

            listener.handleWebSocketDisconnectListener(event)

            verify { lobbyService.isPlayerInLobby(playerId) }
            verify { lobbyService.leave(playerId) }
            verify {
                messagingTemplate.convertAndSend(
                    "/topic/lobby",
                    any<LobbyUpdateMessage>()
                )
            }
        }

        @Test
        fun `authenticated user is removed from authenticated lobby on disconnect`() {
            val event = mockk<SessionDisconnectEvent>()
            every { event.user } returns principal
            every { principal.name } returns playerId
            every { lobbyService.isPlayerInLobby(playerId) } returns false

            val authenticatedLobby = LobbyState(
                lobbyId = "lobby-1",
                joinCode = "ABC123",
                players = listOf(
                    LobbyPlayer(sessionId = playerId, nickname = "Alice", isHost = true, userId = playerId),
                ),
                status = LobbyStatus.WAITING,
                maxPlayers = 6,
            )
            val updatedLobby = authenticatedLobby.copy(players = emptyList(), status = LobbyStatus.CLOSED)
            every { lobbyService.getCurrentLobbyForUser(playerId) } returns authenticatedLobby
            every { lobbyService.leaveLobby(playerId, "lobby-1") } returns updatedLobby

            listener.handleWebSocketDisconnectListener(event)

            verify { lobbyService.leaveLobby(playerId, "lobby-1") }
            verify { messagingTemplate.convertAndSend("/topic/lobbies/ABC123", any<LobbyUpdateMessage>()) }
        }

        @Test
        fun `no authenticated lobby action when getCurrentLobbyForUser returns null`() {
            val event = mockk<SessionDisconnectEvent>()
            every { event.user } returns principal
            every { principal.name } returns playerId
            every { lobbyService.isPlayerInLobby(playerId) } returns false
            every { lobbyService.getCurrentLobbyForUser(playerId) } returns null

            listener.handleWebSocketDisconnectListener(event)

            verify(exactly = 0) { lobbyService.leaveLobby(any(), any()) }
        }

        @Test
        fun `host transfer broadcast is sent after authenticated host disconnects`() {
            val event = mockk<SessionDisconnectEvent>()
            every { event.user } returns principal
            every { principal.name } returns playerId
            every { lobbyService.isPlayerInLobby(playerId) } returns false

            val authenticatedLobby = LobbyState(
                lobbyId = "lobby-1",
                joinCode = "XYZ789",
                players = listOf(
                    LobbyPlayer(sessionId = playerId, nickname = "Host", isHost = true, userId = playerId),
                    LobbyPlayer(sessionId = "other", nickname = "Guest", isHost = false, userId = "other"),
                ),
                status = LobbyStatus.WAITING,
                maxPlayers = 6,
            )
            val updatedLobby = authenticatedLobby.copy(
                players = listOf(LobbyPlayer(sessionId = "other", nickname = "Guest", isHost = true, userId = "other")),
            )
            every { lobbyService.getCurrentLobbyForUser(playerId) } returns authenticatedLobby
            every { lobbyService.leaveLobby(playerId, "lobby-1") } returns updatedLobby

            listener.handleWebSocketDisconnectListener(event)

            verify { messagingTemplate.convertAndSend("/topic/lobbies/XYZ789", any<LobbyUpdateMessage>()) }
        }
    }

    private fun connectedEvent(sessionId: String): SessionConnectedEvent =
        mockk<SessionConnectedEvent> {
            every { user } returns principal
            every { message } returns MessageBuilder.createMessage(
                ByteArray(0),
                SimpMessageHeaderAccessor.create().apply {
                    setSessionId(sessionId)
                }.messageHeaders,
            )
        }

    private fun disconnectEvent(sessionId: String): SessionDisconnectEvent =
        mockk<SessionDisconnectEvent> {
            every { user } returns principal
            every { getSessionId() } returns sessionId
        }
}
