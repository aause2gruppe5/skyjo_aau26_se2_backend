package at.aau.se2.skyjo.controller

import at.aau.se2.skyjo.game.model.DrawSource
import at.aau.se2.skyjo.game.model.GamePhase
import at.aau.se2.skyjo.model.ActionType
import at.aau.se2.skyjo.model.GameActionMessage
import at.aau.se2.skyjo.model.GameUpdateMessage
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
    )

    @Test
    fun `gameAction broadcasts updated state to topic game`() {
        val update = stubGameUpdate()
        whenever(gameService.processAction(any(), any())).thenReturn(update)
        val action = GameActionMessage(ActionType.DRAW, source = DrawSource.DECK)

        controller.gameAction(action, headerWithUser("p1"))

        verify(messagingTemplate).convertAndSend("/topic/game", update)
    }

    @Test
    fun `gameAction sends error to player when processAction throws`() {
        whenever(gameService.processAction(any(), any())).thenThrow(IllegalStateException("not your turn"))
        val action = GameActionMessage(ActionType.DRAW, source = DrawSource.DECK)

        controller.gameAction(action, headerWithUser("p1"))

        verify(messagingTemplate).convertAndSendToUser(eq("p1"), eq("/queue/errors"), any())
    }

    @Test
    fun `gameAction does nothing when user principal is missing`() {
        val header = SimpMessageHeaderAccessor.create()
        val action = GameActionMessage(ActionType.DRAW, source = DrawSource.DECK)

        controller.gameAction(action, header)

        verify(gameService, never()).processAction(any(), any())
        verify(messagingTemplate, never()).convertAndSend(any<String>(), any<Any>())
    }
}
