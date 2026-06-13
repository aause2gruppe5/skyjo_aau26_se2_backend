package at.aau.se2.skyjo.game.service

import at.aau.se2.skyjo.game.error.InvalidMoveException
import at.aau.se2.skyjo.game.model.ActionCardParameters
import at.aau.se2.skyjo.game.model.ActionCardResult
import at.aau.se2.skyjo.game.model.BoardLayout
import at.aau.se2.skyjo.game.model.BoardPosition
import at.aau.se2.skyjo.game.model.BoardSlot
import at.aau.se2.skyjo.game.model.DiscardPile
import at.aau.se2.skyjo.game.model.DrawPile
import at.aau.se2.skyjo.game.model.DrawThreeCardsChoiceMode
import at.aau.se2.skyjo.game.model.DrawThreeCardsDiscardReference
import at.aau.se2.skyjo.game.model.GamePhase
import at.aau.se2.skyjo.game.model.GameState
import at.aau.se2.skyjo.game.model.PendingActionCard
import at.aau.se2.skyjo.game.model.PlayerBoard
import at.aau.se2.skyjo.game.model.PlayerState
import at.aau.se2.skyjo.game.model.PlayActionCardCommand
import at.aau.se2.skyjo.game.model.SkyjoCard
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class DrawThreeCardsActionTest {
    private val engine = SkyjoEngine()
    private val actionCard = SkyjoCard.ActionCard.DrawThreeCards(id = 166)

    @Test
    fun `valid Draw Three Cards play draws three cards privately and keeps action card pending`() {
        val drawn0 = numberCard(30, 3)
        val drawn1 = numberCard(20, 2)
        val drawn2 = numberCard(10, 1)
        val state = stateWithDrawThreeCard(
            drawPile = DrawPile(listOf(numberCard(1, 12), drawn2, drawn1, drawn0)),
        )

        val result = engine.playActionCard(state, PlayActionCardCommand(actionCardIndex = 0))

        val pending = result.pendingActionCard as PendingActionCard.DrawThreeCards
        val privateResult = result.actionCardResult as ActionCardResult.DrawThreeCards
        assertThat(pending.cards).containsExactly(drawn0, drawn1, drawn2)
        assertThat(privateResult.cards).containsExactly(drawn0, drawn1, drawn2)
        assertEquals("p1", pending.actingPlayerId)
        assertEquals(0, pending.actionCardIndex)
        assertEquals(actionCard.id, pending.actionCardId)
        assertThat(result.players[0].actionCards).containsExactly(actionCard)
        assertEquals(0, result.actionDiscardPile.size)
        assertEquals(GamePhase.AWAITING_DRAW, result.phase)
        assertEquals("p1", result.currentPlayerId)
        assertNull(result.drawnCard)
    }

    @Test
    fun `player can choose one drawn card and swap it with own board card`() {
        val target = BoardPosition(0, 0)
        val swappedOut = numberCard(100, 9)
        val drawn0 = numberCard(30, 3)
        val chosen = numberCard(20, 2)
        val drawn2 = numberCard(10, 1)
        val initialDiscard = numberCard(200, 12)
        val startState = stateWithDrawThreeCard(
            board = boardWith(mapOf(target to BoardSlot.Occupied(swappedOut, faceUp = false))),
            drawPile = DrawPile(listOf(numberCard(1, 8), drawn2, chosen, drawn0)),
            discardPile = DiscardPile(listOf(initialDiscard)),
        )
        val pendingState = engine.playActionCard(startState, PlayActionCardCommand(actionCardIndex = 0))

        val result = engine.playActionCard(
            pendingState,
            PlayActionCardCommand(
                actionCardIndex = 0,
                parameters = ActionCardParameters.DrawThreeCardsChoice(
                    targetRow = target.row,
                    targetColumn = target.column,
                    chosenDrawnCardIndex = 1,
                    discardOrder = listOf(
                        DrawThreeCardsDiscardReference.SWAPPED_BOARD_CARD,
                        DrawThreeCardsDiscardReference.DRAWN_CARD_2,
                        DrawThreeCardsDiscardReference.DRAWN_CARD_0,
                    ),
                    targetPlayerId = "p1",
                ),
            ),
        )

        val updatedSlot = result.players[0].board.slotAt(target) as BoardSlot.Occupied
        assertEquals(chosen, updatedSlot.card)
        assertTrue(updatedSlot.faceUp)
        assertThat(result.discardPile.cards)
            .containsExactly(initialDiscard, swappedOut, drawn2, drawn0)
        assertThat(result.players[0].actionCards).isEmpty()
        assertEquals(actionCard, result.actionDiscardPile.topCard())
        assertNull(result.pendingActionCard)
        assertNull(result.actionCardResult)
        assertEquals("p2", result.currentPlayerId)
    }

    @Test
    fun `player can discard all drawn cards and reveal one own face down card`() {
        val revealPosition = BoardPosition(0, 0)
        val revealedCard = numberCard(100, 9)
        val drawn0 = numberCard(30, 3)
        val drawn1 = numberCard(20, 2)
        val drawn2 = numberCard(10, 1)
        val initialDiscard = numberCard(200, 12)
        val startState = stateWithDrawThreeCard(
            board = boardWith(mapOf(revealPosition to BoardSlot.Occupied(revealedCard, faceUp = false))),
            drawPile = DrawPile(listOf(numberCard(1, 8), drawn2, drawn1, drawn0)),
            discardPile = DiscardPile(listOf(initialDiscard)),
        )
        val pendingState = engine.playActionCard(startState, PlayActionCardCommand(actionCardIndex = 0))

        val result = engine.playActionCard(
            pendingState,
            PlayActionCardCommand(
                actionCardIndex = 0,
                parameters = ActionCardParameters.DrawThreeCardsChoice(
                    mode = DrawThreeCardsChoiceMode.DISCARD_ALL_AND_REVEAL,
                    revealRow = revealPosition.row,
                    revealColumn = revealPosition.column,
                    discardOrder = listOf(
                        DrawThreeCardsDiscardReference.DRAWN_CARD_2,
                        DrawThreeCardsDiscardReference.DRAWN_CARD_0,
                        DrawThreeCardsDiscardReference.DRAWN_CARD_1,
                    ),
                    targetPlayerId = "p1",
                ),
            ),
        )

        val updatedSlot = result.players[0].board.slotAt(revealPosition) as BoardSlot.Occupied
        assertEquals(revealedCard, updatedSlot.card)
        assertTrue(updatedSlot.faceUp)
        assertThat(result.discardPile.cards)
            .containsExactly(initialDiscard, drawn2, drawn0, drawn1)
        assertThat(result.players[0].actionCards).isEmpty()
        assertEquals(actionCard, result.actionDiscardPile.topCard())
        assertNull(result.pendingActionCard)
        assertEquals("p2", result.currentPlayerId)
    }

    @Test
    fun `invalid chosen drawn card index is rejected before state changes`() {
        val pendingState = pendingDrawThreeState()

        val exception = assertThrows<InvalidMoveException> {
            engine.playActionCard(
                pendingState,
                PlayActionCardCommand(
                    actionCardIndex = 0,
                    parameters = validChoice().copy(chosenDrawnCardIndex = 3),
                ),
            )
        }

        assertThat(exception).hasMessageContaining("chosenDrawnCardIndex 3 is not available")
        assertThat(pendingState.players[0].actionCards).containsExactly(actionCard)
        assertThat((pendingState.pendingActionCard as PendingActionCard.DrawThreeCards).cards).hasSize(3)
    }

    @Test
    fun `invalid board target is rejected before completion`() {
        val pendingState = pendingDrawThreeState()

        val exception = assertThrows<InvalidMoveException> {
            engine.playActionCard(
                pendingState,
                PlayActionCardCommand(
                    actionCardIndex = 0,
                    parameters = validChoice().copy(targetRow = 99),
                ),
            )
        }

        assertThat(exception).hasMessageContaining("row must be between")
        assertThat(pendingState.players[0].actionCards).containsExactly(actionCard)
        assertThat(pendingState.pendingActionCard).isNotNull
    }

    @Test
    fun `targeting another player's board is rejected`() {
        val pendingState = pendingDrawThreeState()

        val exception = assertThrows<InvalidMoveException> {
            engine.playActionCard(
                pendingState,
                PlayActionCardCommand(
                    actionCardIndex = 0,
                    parameters = validChoice().copy(targetPlayerId = "p2"),
                ),
            )
        }

        assertThat(exception).hasMessageContaining("can only target your own board")
        assertThat(pendingState.players[0].actionCards).containsExactly(actionCard)
        assertThat(pendingState.pendingActionCard).isNotNull
    }

    @Test
    fun `discard all and reveal rejects targeting another player's board`() {
        val pendingState = pendingDrawThreeState()

        val exception = assertThrows<InvalidMoveException> {
            engine.playActionCard(
                pendingState,
                PlayActionCardCommand(
                    actionCardIndex = 0,
                    parameters = validDiscardAllChoice().copy(targetPlayerId = "p2"),
                ),
            )
        }

        assertThat(exception).hasMessageContaining("can only target your own board")
        assertThat(pendingState.players[0].actionCards).containsExactly(actionCard)
        assertThat(pendingState.pendingActionCard).isNotNull
    }

    @Test
    fun `discard order must contain exactly unchosen drawn cards and swapped board card`() {
        val pendingState = pendingDrawThreeState()

        val exception = assertThrows<InvalidMoveException> {
            engine.playActionCard(
                pendingState,
                PlayActionCardCommand(
                    actionCardIndex = 0,
                    parameters = validChoice().copy(
                        chosenDrawnCardIndex = 1,
                        discardOrder = listOf(
                            DrawThreeCardsDiscardReference.DRAWN_CARD_0,
                            DrawThreeCardsDiscardReference.DRAWN_CARD_1,
                            DrawThreeCardsDiscardReference.SWAPPED_BOARD_CARD,
                        ),
                    ),
                ),
            )
        }

        assertThat(exception).hasMessageContaining("two unchosen drawn cards")
    }

    @Test
    fun `discard all and reveal requires all three drawn cards in discard order`() {
        val pendingState = pendingDrawThreeState()

        val exception = assertThrows<InvalidMoveException> {
            engine.playActionCard(
                pendingState,
                PlayActionCardCommand(
                    actionCardIndex = 0,
                    parameters = validDiscardAllChoice().copy(
                        discardOrder = listOf(
                            DrawThreeCardsDiscardReference.DRAWN_CARD_0,
                            DrawThreeCardsDiscardReference.DRAWN_CARD_1,
                            DrawThreeCardsDiscardReference.SWAPPED_BOARD_CARD,
                        ),
                    ),
                ),
            )
        }

        assertThat(exception).hasMessageContaining("all three drawn cards")
        assertThat(pendingState.players[0].actionCards).containsExactly(actionCard)
        assertThat(pendingState.pendingActionCard).isNotNull
    }

    @Test
    fun `discard all and reveal rejects face up reveal target before state changes`() {
        val revealPosition = BoardPosition(0, 0)
        val board = boardWith(mapOf(revealPosition to BoardSlot.Occupied(numberCard(100, 9), faceUp = true)))
        val pendingState = engine.playActionCard(
            stateWithDrawThreeCard(
                board = board,
                drawPile = DrawPile(
                    listOf(
                        numberCard(1, 12),
                        numberCard(10, 1),
                        numberCard(20, 2),
                        numberCard(30, 3),
                    ),
                ),
            ),
            PlayActionCardCommand(actionCardIndex = 0),
        )
        val discardPileBefore = pendingState.discardPile
        val boardBefore = pendingState.players[0].board

        val exception = assertThrows<InvalidMoveException> {
            engine.playActionCard(
                pendingState,
                PlayActionCardCommand(
                    actionCardIndex = 0,
                    parameters = validDiscardAllChoice().copy(
                        revealRow = revealPosition.row,
                        revealColumn = revealPosition.column,
                    ),
                ),
            )
        }

        assertThat(exception).hasMessageContaining("face-down occupied slot")
        assertEquals(discardPileBefore, pendingState.discardPile)
        assertEquals(boardBefore, pendingState.players[0].board)
        assertThat(pendingState.players[0].actionCards).containsExactly(actionCard)
        assertThat(pendingState.pendingActionCard).isNotNull
    }

    @Test
    fun `draw three cards replenishes draw pile consistently when needed`() {
        val drawnFromDeck = numberCard(30, 3)
        val recycledA = numberCard(40, 4)
        val recycledB = numberCard(50, 5)
        val protectedDiscardTop = numberCard(60, 6)
        val state = stateWithDrawThreeCard(
            drawPile = DrawPile(listOf(drawnFromDeck)),
            discardPile = DiscardPile(listOf(recycledA, recycledB, protectedDiscardTop)),
        )

        val result = engine.playActionCard(state, PlayActionCardCommand(actionCardIndex = 0))

        val pending = result.pendingActionCard as PendingActionCard.DrawThreeCards
        assertThat(pending.cards).containsExactlyInAnyOrder(drawnFromDeck, recycledA, recycledB)
        assertThat(result.discardPile.cards).containsExactly(protectedDiscardTop)
        assertEquals(1, result.shuffleCount)
    }

    @Test
    fun `other turn actions are rejected while draw three cards is pending`() {
        val pendingState = pendingDrawThreeState()

        val exception = assertThrows<InvalidMoveException> {
            engine.drawFromDeck(pendingState)
        }

        assertThat(exception).hasMessageContaining("pending action card must be completed first")
    }

    private fun pendingDrawThreeState(): GameState =
        engine.playActionCard(
            stateWithDrawThreeCard(
                drawPile = DrawPile(
                    listOf(
                        numberCard(1, 12),
                        numberCard(10, 1),
                        numberCard(20, 2),
                        numberCard(30, 3),
                    ),
                ),
            ),
            PlayActionCardCommand(actionCardIndex = 0),
        )

    private fun validChoice(): ActionCardParameters.DrawThreeCardsChoice =
        ActionCardParameters.DrawThreeCardsChoice(
            mode = DrawThreeCardsChoiceMode.KEEP_ONE_AND_SWAP,
            targetRow = 0,
            targetColumn = 0,
            chosenDrawnCardIndex = 0,
            discardOrder = listOf(
                DrawThreeCardsDiscardReference.DRAWN_CARD_1,
                DrawThreeCardsDiscardReference.DRAWN_CARD_2,
                DrawThreeCardsDiscardReference.SWAPPED_BOARD_CARD,
            ),
            targetPlayerId = "p1",
        )

    private fun validDiscardAllChoice(): ActionCardParameters.DrawThreeCardsChoice =
        ActionCardParameters.DrawThreeCardsChoice(
            mode = DrawThreeCardsChoiceMode.DISCARD_ALL_AND_REVEAL,
            revealRow = 0,
            revealColumn = 0,
            discardOrder = listOf(
                DrawThreeCardsDiscardReference.DRAWN_CARD_0,
                DrawThreeCardsDiscardReference.DRAWN_CARD_1,
                DrawThreeCardsDiscardReference.DRAWN_CARD_2,
            ),
            targetPlayerId = "p1",
        )

    private fun stateWithDrawThreeCard(
        board: PlayerBoard = boardWith(),
        drawPile: DrawPile,
        discardPile: DiscardPile = DiscardPile.empty(),
    ): GameState =
        GameState(
            players = listOf(
                PlayerState(id = "p1", board = board, actionCards = listOf(actionCard)),
                PlayerState(id = "p2", board = boardWith()),
            ),
            currentPlayerIndex = 0,
            drawPile = drawPile,
            discardPile = discardPile,
            phase = GamePhase.AWAITING_DRAW,
            shuffleSeed = 7L,
        )

    private fun boardWith(overrides: Map<BoardPosition, BoardSlot> = emptyMap()): PlayerBoard {
        val slots = BoardLayout.POSITIONS.associateWith { position ->
            overrides[position] ?: BoardSlot.Occupied(
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
