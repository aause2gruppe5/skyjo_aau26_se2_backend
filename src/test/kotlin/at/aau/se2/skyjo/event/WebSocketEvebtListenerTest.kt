package at.aau.se2.skyjo.event

import io.mockk.*
import io.mockk.impl.annotations.MockK
import io.mockk.impl.annotations.RelaxedMockK
import io.mockk.junit5.MockKExtension
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.springframework.messaging.simp.SimpMessageSendingOperations
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

    private lateinit var listener: WebSocketEventListener

    @MockK
    lateinit var principal: Principal

    @BeforeEach
    fun setUp() {
        listener = WebSocketEventListener(messagingTemplate, lobbyService, gameService = null)
    }

    private val playerId = "player-123"

    @Nested
    inner class HandleWebSocketConnectListenerTests {

        @Test
        fun `loggt neue Verbindung, wenn User vorhanden ist`() {
            val event = mockk<SessionConnectedEvent>()
            every { event.user } returns principal
            every { principal.name } returns playerId

            // Ausführung (Wir testen hier primär, dass keine Exception fliegt,
            // da die Methode nur loggt und keinen State ändert)
            listener.handleWebSocketConnectListener(event)

            verify { event.user }
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

            listener.handleWebSocketDisconnectListener(event)

            verify { lobbyService.isPlayerInLobby(playerId) }
            verify(exactly = 0) { lobbyService.leave(any()) }
            verify { messagingTemplate wasNot Called }
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
                every { players } returns emptyList()
                every { status } returns mockk() // Nimmt dein LobbyStatus Enum
                every { maxPlayers } returns 4
            }
            every { lobbyService.leave(playerId) } returns updatedState

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
    }
}