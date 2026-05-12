package at.aau.se2.skyjo.controller

import at.aau.se2.skyjo.model.MessageType
import at.aau.se2.skyjo.model.PlayerMessage
import at.aau.se2.skyjo.game.service.SkyjoGameService
import at.aau.se2.skyjo.persistence.GameRepository
import at.aau.se2.skyjo.service.ConnectionService
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.verifyNoInteractions
import org.mockito.kotlin.whenever
import org.springframework.messaging.simp.SimpMessageHeaderAccessor
import org.springframework.messaging.simp.SimpMessageSendingOperations

class GameControllerTest {

    private val connectionService: ConnectionService = mock()
    private val gameService: SkyjoGameService = mock()
    private val gameRepository: GameRepository = mock()
    private val messagingTemplate: SimpMessageSendingOperations = mock()
    private val controller = GameController(connectionService, gameService, gameRepository, messagingTemplate)

    @Test
    fun `joinGame registers player and returns PLAYER_JOINED`() {
        val headerAccessor = SimpMessageHeaderAccessor.create()
        headerAccessor.sessionId = "s1"

        val message = PlayerMessage("Alice")

        val result = controller.joinGame(message, headerAccessor)

        verify(connectionService).registerSession("s1", "Alice", null)
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
    fun `joinGame rejoins player when gameId matches`() {
        val headerAccessor = SimpMessageHeaderAccessor.create()
        headerAccessor.sessionId = "s2"

        val message = PlayerMessage("Bob", gameId = "game-123")
        whenever(gameRepository.getPlayerGame("Bob")).thenReturn("game-123")
        whenever(gameService.getGameState()).thenReturn(null)

        val result = controller.joinGame(message, headerAccessor)

        verify(connectionService).registerSession("s2", "Bob", "game-123")
        verify(gameRepository).savePlayerSession("Bob", "game-123", connected = true)
        assertEquals(MessageType.PLAYER_REJOINED, result.type)
        assertEquals("Bob rejoined.", result.content)
    }

    @Test
    fun `leaveGame removes session and returns PLAYER_LEFT`() {
        val headerAccessor = SimpMessageHeaderAccessor.create()
        headerAccessor.sessionId = "s1"

        whenever(connectionService.removeSession("s1")).thenReturn("Alice")

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

        whenever(connectionService.removeSession("s1")).thenReturn(null)

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

    @Test
    fun `joinGame saves player session when active game exists`() {
        val headerAccessor = SimpMessageHeaderAccessor.create()
        headerAccessor.sessionId = "s3"
        val message = PlayerMessage("Carol")
        whenever(gameService.getActiveGameId()).thenReturn("active-game-id")

        val result = controller.joinGame(message, headerAccessor)

        verify(connectionService).registerSession("s3", "Carol", "active-game-id")
        verify(gameRepository).savePlayerSession("Carol", "active-game-id", connected = true)
        assertEquals(MessageType.PLAYER_JOINED, result.type)
    }

    @Test
    fun `joinGame rejoins and sends game state when state available`() {
        val headerAccessor = SimpMessageHeaderAccessor.create()
        headerAccessor.sessionId = "s4"
        val gameState = at.aau.se2.skyjo.game.model.GameState(
            phase = at.aau.se2.skyjo.game.model.GamePhase.AWAITING_DRAW
        )
        val message = PlayerMessage("Dave", gameId = "game-xyz")
        whenever(gameRepository.getPlayerGame("Dave")).thenReturn("game-xyz")
        whenever(gameService.getGameState()).thenReturn(gameState)

        controller.joinGame(message, headerAccessor)

        verify(messagingTemplate).convertAndSendToUser("s4", "/queue/gamestate", gameState)
    }

    @Test
    fun `joinGame rejoins without sending state when no active game state`() {
        val headerAccessor = SimpMessageHeaderAccessor.create()
        headerAccessor.sessionId = "s5"
        val message = PlayerMessage("Eve", gameId = "game-xyz")
        whenever(gameRepository.getPlayerGame("Eve")).thenReturn("game-xyz")
        whenever(gameService.getGameState()).thenReturn(null)

        val result = controller.joinGame(message, headerAccessor)

        verifyNoInteractions(messagingTemplate)
        assertEquals(MessageType.PLAYER_REJOINED, result.type)
    }
}
