package at.aau.se2.skyjo.controller

import at.aau.se2.skyjo.model.MessageType
import at.aau.se2.skyjo.model.PlayerMessage
import at.aau.se2.skyjo.service.ConnectionService
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.mockito.kotlin.mock
import org.mockito.kotlin.verify
import org.springframework.messaging.simp.SimpMessageHeaderAccessor

class GameControllerTest {

    private val connectionService: ConnectionService = mock()
    private val controller = GameController(connectionService)

    @Test
    fun `joinGame registers player and returns PLAYER_JOINED`() {
        val headerAccessor = SimpMessageHeaderAccessor.create()
        headerAccessor.sessionId = "s1"

        val message = PlayerMessage("Alice")

        val result = controller.joinGame(message, headerAccessor)

        verify(connectionService).registerSession("s1", "Alice")
        assertEquals(MessageType.PLAYER_JOINED, result.type)
        assertEquals("Alice joined.", result.content)
        assertEquals("Alice", result.playerName)
    }

    @Test
    fun `joinGame returns ERROR if sessionId is null`() {
        val headerAccessor = SimpMessageHeaderAccessor.create()
        val message = PlayerMessage("Alice")

        val result = controller.joinGame(message, headerAccessor)

        assertEquals(MessageType.ERROR, result.type)
    }

    @Test
    fun `leaveGame removes session and returns PLAYER_LEFT`() {
        val headerAccessor = SimpMessageHeaderAccessor.create()
        headerAccessor.sessionId = "s1"

        org.mockito.kotlin.whenever(connectionService.removeSession("s1"))
            .thenReturn("Alice")

        val result = controller.leaveGame(headerAccessor)

        verify(connectionService).removeSession("s1")
        assertEquals(MessageType.PLAYER_LEFT, result.type)
        assertEquals("Alice left.", result.content)
        assertEquals("Alice", result.playerName)
    }
    @Test
    fun `leaveGame returns Unknown when session is not found`() {
        val headerAccessor = SimpMessageHeaderAccessor.create()
        headerAccessor.sessionId = "s1"

        org.mockito.kotlin.whenever(connectionService.removeSession("s1"))
            .thenReturn(null)

        val result = controller.leaveGame(headerAccessor)

        assertEquals(MessageType.PLAYER_LEFT, result.type)
        assertEquals("Unknown left.", result.content)
        assertNull(result.playerName)
    }
    @Test
    fun `leaveGame returns ERROR if sessionId is null`() {
        val headerAccessor = SimpMessageHeaderAccessor.create()

        val result = controller.leaveGame(headerAccessor)

        assertEquals(MessageType.ERROR, result.type)
    }
}