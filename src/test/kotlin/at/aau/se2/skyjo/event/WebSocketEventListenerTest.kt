package at.aau.se2.skyjo.event

import at.aau.se2.skyjo.model.MessageType
import at.aau.se2.skyjo.model.ServerMessage
import at.aau.se2.skyjo.persistence.GameRepository
import at.aau.se2.skyjo.service.ConnectionService
import io.mockk.*
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.springframework.messaging.Message
import org.springframework.messaging.MessageHeaders
import org.springframework.messaging.simp.SimpMessageSendingOperations
import org.springframework.web.socket.messaging.SessionConnectedEvent
import org.springframework.web.socket.messaging.SessionDisconnectEvent

class WebSocketEventListenerTest {

    private val messagingTemplate: SimpMessageSendingOperations = mockk(relaxed = true)
    private val connectionService: ConnectionService = mockk()
    private val gameRepository: GameRepository = mockk(relaxed = true)

    private val listener = WebSocketEventListener(messagingTemplate, connectionService, gameRepository)

    @AfterEach
    fun tearDown() = clearAllMocks()

    @Test
    fun `handleWebSocketConnectListener logs connection`() {
        val message = mockk<Message<ByteArray>>()
        val headers = mockk<MessageHeaders>()
        every { message.headers } returns headers
        every { headers["simpSessionId"] } returns "session-1"

        val event = mockk<SessionConnectedEvent>()
        every { event.message } returns message

        listener.handleWebSocketConnectListener(event)
    }

    @Test
    fun `handleWebSocketDisconnectListener broadcasts PLAYER_LEFT for known session`() {
        val event = mockk<SessionDisconnectEvent>()
        every { event.sessionId } returns "session-1"
        every { connectionService.removeSession("session-1") } returns "Alice"

        listener.handleWebSocketDisconnectListener(event)

        verify { gameRepository.markDisconnected("Alice") }
        verify {
            messagingTemplate.convertAndSend(
                "/topic/public",
                match<ServerMessage> { it.type == MessageType.PLAYER_LEFT && it.playerName == "Alice" }
            )
        }
    }

    @Test
    fun `handleWebSocketDisconnectListener does nothing for unknown session`() {
        val event = mockk<SessionDisconnectEvent>()
        every { event.sessionId } returns "session-unknown"
        every { connectionService.removeSession("session-unknown") } returns null

        listener.handleWebSocketDisconnectListener(event)

        verify(exactly = 0) { gameRepository.markDisconnected(any()) }
        verify(exactly = 0) { messagingTemplate.convertAndSend(any<String>(), any<Any>()) }
    }

    @Test
    fun `handleWebSocketDisconnectListener works without repository`() {
        val listenerNoRepo = WebSocketEventListener(messagingTemplate, connectionService, null)
        val event = mockk<SessionDisconnectEvent>()
        every { event.sessionId } returns "session-2"
        every { connectionService.removeSession("session-2") } returns "Bob"

        listenerNoRepo.handleWebSocketDisconnectListener(event)

        verify {
            messagingTemplate.convertAndSend(
                "/topic/public",
                match<ServerMessage> { it.type == MessageType.PLAYER_LEFT && it.playerName == "Bob" }
            )
        }
    }
}
