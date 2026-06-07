package at.aau.se2.skyjo.controller

import at.aau.se2.skyjo.game.model.DrawSource
import at.aau.se2.skyjo.game.model.GamePhase
import at.aau.se2.skyjo.game.model.PlayActionCardCommand
import at.aau.se2.skyjo.game.model.BoardLineTargetType
import at.aau.se2.skyjo.model.ActionCardResultMessage
import at.aau.se2.skyjo.model.ActionCardResultType
import at.aau.se2.skyjo.model.ActionType
import at.aau.se2.skyjo.model.CardDto
import at.aau.se2.skyjo.model.CardType
import at.aau.se2.skyjo.model.CheatPeekResultMessage
import at.aau.se2.skyjo.model.GameActionMessage
import at.aau.se2.skyjo.model.GameUpdateMessage
import at.aau.se2.skyjo.model.PlayActionCardMessageResult
import at.aau.se2.skyjo.service.GameService
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import org.springframework.messaging.simp.SimpMessageHeaderAccessor
import org.springframework.messaging.simp.SimpMessageSendingOperations
import java.security.Principal

class GameControllerTest {

    private val gameService: GameService = mock()
    private val messagingTemplate: SimpMessageSendingOperations = mock()
    private val controller = GameController(gameService, messagingTemplate)

    private fun headerWithUser(userId: String): SimpMessageHeaderAccessor {
        val header = SimpMessageHeaderAccessor.create()
        header.user = Principal { userId }
        return header
    }

    private fun stubGameUpdate(): GameUpdateMessage = GameUpdateMessage(
        phase = GamePhase.AWAITING_DRAW,
        currentPlayerId = "p1",
        players = emptyList(),
        discardTopCard = null,
        drawnCard = null,
        roundResult = null,
        roundNumber = 1,
        totalScores = emptyList(),
        gameOver = false,
        gameId = "game-1",
        lobbyId = "lobby-1",
    )

    @Test
    fun `gameAction broadcasts updated state to game-specific topic`() {
        val update = stubGameUpdate()
        whenever(gameService.processAction(any(), any())).thenReturn(update)
        val action = GameActionMessage(ActionType.DRAW, source = DrawSource.DECK)

        controller.gameAction(action, headerWithUser("p1"))

        verify(messagingTemplate).convertAndSend("/topic/games/game-1", update)
    }

    @Test
    fun `gameAction sends error to player when processAction throws`() {
        whenever(gameService.processAction(any(), any())).thenThrow(IllegalStateException("not your turn"))
        val action = GameActionMessage(ActionType.DRAW, source = DrawSource.DECK)

        controller.gameAction(action, headerWithUser("p1"))

        verify(messagingTemplate).convertAndSendToUser(eq("p1"), eq("/queue/errors"), any<Any>())
    }

    @Test
    fun `gameAction does nothing when user principal is missing`() {
        val header = SimpMessageHeaderAccessor.create()
        val action = GameActionMessage(ActionType.DRAW, source = DrawSource.DECK)

        controller.gameAction(action, header)

        verify(gameService, never()).processAction(any(), any())
        verify(messagingTemplate, never()).convertAndSend(any<String>(), any<Any>())
    }

    @Test
    fun `cheatPeekDrawPile sends private result to acting user`() {
        val result = CheatPeekResultMessage(
            card = CardDto(id = 7, value = 4, type = CardType.NUMBER),
            remainingCheatPeeks = 2,
        )
        whenever(gameService.cheatPeekDrawPile("p1")).thenReturn(result)

        controller.cheatPeekDrawPile(headerWithUser("p1"))

        verify(messagingTemplate).convertAndSendToUser("p1", "/queue/cheat-peek-results", result)
    }

    @Test
    fun `cheatPeekDrawPile sends error to player when service throws`() {
        whenever(gameService.cheatPeekDrawPile("p1")).thenThrow(IllegalStateException("no cheat peeks left"))

        controller.cheatPeekDrawPile(headerWithUser("p1"))

        verify(messagingTemplate).convertAndSendToUser(eq("p1"), eq("/queue/errors"), any())
        verify(messagingTemplate, never()).convertAndSendToUser(eq("p1"), eq("/queue/cheat-peek-results"), any<Any>())
    }

    @Test
    fun `cheatPeekDrawPile does nothing when user principal is missing`() {
        val header = SimpMessageHeaderAccessor.create()

        controller.cheatPeekDrawPile(header)

        verify(gameService, never()).cheatPeekDrawPile(any())
        verify(messagingTemplate, never()).convertAndSendToUser(any<String>(), any<String>(), any<Any>())
    }

    @Test
    fun `playActionCard broadcasts public update and sends private result only to acting user`() {
        val update = stubGameUpdate()
        val privateResult = ActionCardResultMessage(
            type = ActionCardResultType.ENLIGHTENMENT,
            actionCardIndex = 0,
            targetPlayerId = "p1",
            targetType = BoardLineTargetType.ROW,
            lineIndex = 0,
            inspectedValues = listOf(1, 2, 3, 4),
            inspectedCards = emptyList(),
        )
        val command = PlayActionCardCommand(actionCardIndex = 0)
        whenever(gameService.playActionCard(any(), any())).thenReturn(
            PlayActionCardMessageResult(
                gameUpdate = update,
                privateActionCardResults = mapOf("p1" to privateResult),
            ),
        )

        controller.playActionCard(command, headerWithUser("p1"))

        verify(messagingTemplate).convertAndSend("/topic/games/game-1", update)
        verify(messagingTemplate).convertAndSendToUser("p1", "/queue/action-card-results", privateResult)
        verify(messagingTemplate, never()).convertAndSendToUser(eq("p2"), eq("/queue/action-card-results"), any())
    }

    @Test
    fun `playActionCard sends error to player when service throws`() {
        whenever(gameService.playActionCard(any(), any())).thenThrow(IllegalStateException("not your turn"))
        val command = PlayActionCardCommand(actionCardIndex = 0)

        controller.playActionCard(command, headerWithUser("p1"))

        verify(messagingTemplate).convertAndSendToUser(eq("p1"), eq("/queue/errors"), any())
        verify(messagingTemplate, never()).convertAndSend(eq("/topic/game"), any<Any>())
    }

    @Test
    fun `playActionCard does nothing when user principal is missing`() {
        val header = SimpMessageHeaderAccessor.create()
        val command = PlayActionCardCommand(actionCardIndex = 0)

        controller.playActionCard(command, header)

        verify(gameService, never()).playActionCard(any(), any())
        verify(messagingTemplate, never()).convertAndSend(any<String>(), any<Any>())
    }
}
