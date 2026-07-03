package at.aau.se2.skyjo.service

import at.aau.se2.skyjo.game.error.InvalidMoveException
import at.aau.se2.skyjo.game.model.ActionCardParameters
import at.aau.se2.skyjo.game.model.BoardLayout
import at.aau.se2.skyjo.game.model.BoardPosition
import at.aau.se2.skyjo.game.model.BoardSlot
import at.aau.se2.skyjo.game.model.DiscardPile
import at.aau.se2.skyjo.game.model.DrawPile
import at.aau.se2.skyjo.game.model.DrawThreeCardsDiscardReference
import at.aau.se2.skyjo.game.model.GamePhase
import at.aau.se2.skyjo.game.model.GameState
import at.aau.se2.skyjo.game.model.PendingActionCard
import at.aau.se2.skyjo.game.model.PlayerBoard
import at.aau.se2.skyjo.game.model.PlayerState
import at.aau.se2.skyjo.game.model.PlayActionCardCommand
import at.aau.se2.skyjo.game.model.SkyjoCard
import at.aau.se2.skyjo.game.service.SkyjoEngine
import at.aau.se2.skyjo.model.ActionCardResultType
import at.aau.se2.skyjo.model.lobby.LobbyPlayer
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class GameServiceDrawThreeCardsTest {
    private val engine = SkyjoEngine()
    private lateinit var service: GameService

    private val player1Id = "player1"
    private val player2Id = "player2"
    private val players = listOf(
        LobbyPlayer(sessionId = player1Id, nickname = "Alice", isHost = true),
        LobbyPlayer(sessionId = player2Id, nickname = "Bob", isHost = false),
    )

    @BeforeEach
    fun setUp() {
        service = GameService(engine, null)
        service.startGame(players)
    }

    @Test
    fun `Draw Three Cards start returns drawn cards only in private action result`() {
        setInternalGameStateForDrawThree(service, controlledDrawThreeState())

        val result = service.playActionCard(player1Id, PlayActionCardCommand(actionCardIndex = 0))

        val privateResult = result.privateActionCardResults[player1Id]!!
        assertEquals(setOf(player1Id), result.privateActionCardResults.keys)
        assertEquals(ActionCardResultType.DRAW_THREE_CARDS, privateResult.type)
        assertEquals(listOf(3, 2, 1), privateResult.drawnCards.map { it.value })
        assertNull(result.privateActionCardResults[player2Id])
        assertNull(result.gameUpdate.drawnCard)
        assertEquals(player1Id, result.gameUpdate.currentPlayerId)
        assertEquals(GamePhase.AWAITING_DRAW, result.gameUpdate.phase)
        assertTrue(
            result.gameUpdate.players
                .flatMap { it.board }
                .flatten()
                .filter { it.faceUp == false }
                .all { it.card == null },
        )
    }

    @Test
    fun `invalid Draw Three Cards completion keeps stored state unchanged and does not leak pending cards publicly`() {
        setInternalGameStateForDrawThree(service, controlledDrawThreeState())
        service.playActionCard(player1Id, PlayActionCardCommand(actionCardIndex = 0))
        val pendingState = getInternalGameStateForDrawThree(service)

        val exception = assertThrows<InvalidMoveException> {
            service.playActionCard(
                player1Id,
                PlayActionCardCommand(
                    actionCardIndex = 0,
                    parameters = validCompletionChoice().copy(chosenDrawnCardIndex = 99),
                ),
            )
        }

        assertTrue(exception.message!!.contains("chosenDrawnCardIndex 99 is not available"))
        assertEquals(pendingState, getInternalGameStateForDrawThree(service))
        assertTrue(getInternalGameStateForDrawThree(service).pendingActionCard is PendingActionCard.DrawThreeCards)
        assertNull(service.getCurrentState(player1Id)!!.drawnCard)
    }

    private fun validCompletionChoice(): ActionCardParameters.DrawThreeCardsChoice =
        ActionCardParameters.DrawThreeCardsChoice(
            targetRow = 0,
            targetColumn = 0,
            chosenDrawnCardIndex = 0,
            discardOrder = listOf(
                DrawThreeCardsDiscardReference.DRAWN_CARD_1,
                DrawThreeCardsDiscardReference.DRAWN_CARD_2,
                DrawThreeCardsDiscardReference.SWAPPED_BOARD_CARD,
            ),
            targetPlayerId = player1Id,
        )

    private fun controlledDrawThreeState(): GameState {
        val drawn0 = numberCard(30, 3)
        val drawn1 = numberCard(20, 2)
        val drawn2 = numberCard(10, 1)
        return GameState(
            players = listOf(
                PlayerState(
                    id = player1Id,
                    board = hiddenBoard(),
                    actionCards = listOf(SkyjoCard.ActionCard.DrawThreeCards(id = 166)),
                ),
                PlayerState(id = player2Id, board = hiddenBoard()),
            ),
            currentPlayerIndex = 0,
            drawPile = DrawPile(listOf(numberCard(1, 12), drawn2, drawn1, drawn0)),
            discardPile = DiscardPile(listOf(numberCard(99, 9))),
            phase = GamePhase.AWAITING_DRAW,
            shuffleSeed = 11L,
        )
    }

    private fun hiddenBoard(): PlayerBoard {
        val slots = BoardLayout.POSITIONS.associateWith { position ->
            BoardSlot.Occupied(
                card = numberCard(
                    id = 1000 + position.row * BoardLayout.COLUMNS + position.column,
                    value = position.row * BoardLayout.COLUMNS + position.column,
                ),
                faceUp = false,
            )
        }
        return PlayerBoard(slots)
    }

    private fun numberCard(id: Int, value: Int): SkyjoCard.NumberCard =
        SkyjoCard.NumberCard(id = id, value = value)
}

private fun getInternalGameStateForDrawThree(service: GameService): GameState =
    service.currentGameStateForTest()

private fun setInternalGameStateForDrawThree(service: GameService, state: GameState) {
    service.seedGameForTest(state)
}
