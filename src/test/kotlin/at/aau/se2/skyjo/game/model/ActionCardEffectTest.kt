package at.aau.se2.skyjo.game.model

import at.aau.se2.skyjo.game.error.InvalidMoveException
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class ActionCardEffectTest {
    @Test
    fun placeholderActionCardMapsToPlaceholderEffect(){
        val card = SkyjoCard.ActionCard.Placeholder(id = 1)

        assertSame(ActionCardEffect.Placeholder, card.toEffect())
    }

    @Test
    fun defenseActionCardMapsToDefenseEffect(){
        val card = SkyjoCard.ActionCard.Defense(id = 151)

        assertSame(ActionCardEffect.Defense, card.toEffect())
    }

    @Test
    fun playerSwapCardMapsToPlayerSwapEffect(){
        val card = SkyjoCard.ActionCard.PlayerSwapCard(id = 152)

        assertSame(ActionCardEffect.PlayerSwap, card.toEffect())
    }

    @Test
    fun placeholderActionCardEffectKeepsStateUnchanged(){
        val state = GameState()

        assertSame(state, ActionCardEffect.Placeholder.apply(state, ActionCardParameters.None))
    }

    @Test
    fun defenseActionCardEffectAddsPendingExtraTurn(){
        val state = GameState(pendingExtraTurns = 1)

        val result = ActionCardEffect.Defense.apply(state, ActionCardParameters.None)

        assertSame(state.players, result.players)
        assertEquals(2, result.pendingExtraTurns)
    }

    @Test
    fun playerSwapActionCardEffectSwapsCardsBetweenPlayers(){
        val card1 = SkyjoCard.NumberCard(id = 10, value = 3)
        val card2 = SkyjoCard.NumberCard(id = 20, value = 7)
        val pos1 = BoardPosition(0, 0)
        val pos2 = BoardPosition(1, 1)
        val state = GameState(
            players = listOf(
                PlayerState(id = "p1", board = buildBoard(mapOf(pos1 to BoardSlot.Occupied(card1, faceUp = true)))),
                PlayerState(id = "p2", board = buildBoard(mapOf(pos2 to BoardSlot.Occupied(card2, faceUp = false)))),
            ),
        )

        val result = ActionCardEffect.PlayerSwap.apply(
            state,
            ActionCardParameters.PlayerSwap(
                player1Id = "p1",
                player1Position = pos1,
                player2Id = "p2",
                player2Position = pos2,
            ),
        )

        val p1Slot = result.players.first { it.id == "p1" }.board.slotAt(pos1) as BoardSlot.Occupied
        val p2Slot = result.players.first { it.id == "p2" }.board.slotAt(pos2) as BoardSlot.Occupied
        assertEquals(card2, p1Slot.card)
        assertEquals(card1, p2Slot.card)
        assertTrue(p1Slot.faceUp)
        assertFalse(p2Slot.faceUp)
    }

    @Test
    fun playerSwapActionCardEffectRejectsSamePlayer(){
        val pos = BoardPosition(0, 0)
        val state = GameState(
            players = listOf(
                PlayerState(
                    id = "p1",
                    board = buildBoard(mapOf(pos to BoardSlot.Occupied(SkyjoCard.NumberCard(1, 1), faceUp = true))),
                ),
            ),
        )

        assertThrows(InvalidMoveException::class.java) {
            ActionCardEffect.PlayerSwap.apply(
                state,
                ActionCardParameters.PlayerSwap("p1", pos, "p1", BoardPosition(0, 1)),
            )
        }
    }

    @Test
    fun playerSwapActionCardEffectRejectsMissingParameters() {
        assertThrows(IllegalArgumentException::class.java) {
            ActionCardEffect.PlayerSwap.apply(GameState(), ActionCardParameters.None)
        }
    }

    @Test
    fun playerSwapActionCardEffectRejectsUnknownFirstPlayer() {
        val pos = BoardPosition(0, 0)
        val state = GameState(
            players = listOf(
                PlayerState(
                    id = "p2",
                    board = buildBoard(mapOf(pos to BoardSlot.Occupied(SkyjoCard.NumberCard(1, 1), faceUp = true))),
                ),
            ),
        )

        val exception = assertThrows(InvalidMoveException::class.java) {
            ActionCardEffect.PlayerSwap.apply(
                state,
                ActionCardParameters.PlayerSwap("p1", pos, "p2", pos),
            )
        }

        assertTrue(exception.message!!.contains("player p1 not found"))
    }

    @Test
    fun playerSwapActionCardEffectRejectsUnknownSecondPlayer() {
        val pos = BoardPosition(0, 0)
        val state = GameState(
            players = listOf(
                PlayerState(
                    id = "p1",
                    board = buildBoard(mapOf(pos to BoardSlot.Occupied(SkyjoCard.NumberCard(1, 1), faceUp = true))),
                ),
            ),
        )

        val exception = assertThrows(InvalidMoveException::class.java) {
            ActionCardEffect.PlayerSwap.apply(
                state,
                ActionCardParameters.PlayerSwap("p1", pos, "p2", pos),
            )
        }

        assertTrue(exception.message!!.contains("player p2 not found"))
    }

    @Test
    fun playerSwapActionCardEffectRejectsClearedFirstSlot() {
        val pos1 = BoardPosition(0, 0)
        val pos2 = BoardPosition(0, 1)
        val state = GameState(
            players = listOf(
                PlayerState(id = "p1", board = buildBoard(mapOf(pos1 to BoardSlot.Cleared))),
                PlayerState(id = "p2", board = buildBoard(mapOf(pos2 to BoardSlot.Occupied(SkyjoCard.NumberCard(2, 2), faceUp = true)))),
            ),
        )

        val exception = assertThrows(InvalidMoveException::class.java) {
            ActionCardEffect.PlayerSwap.apply(
                state,
                ActionCardParameters.PlayerSwap("p1", pos1, "p2", pos2),
            )
        }

        assertTrue(exception.message!!.contains("slot $pos1 of player p1 is not occupied"))
    }

    @Test
    fun playerSwapActionCardEffectRejectsClearedSecondSlot() {
        val pos1 = BoardPosition(0, 0)
        val pos2 = BoardPosition(0, 1)
        val state = GameState(
            players = listOf(
                PlayerState(id = "p1", board = buildBoard(mapOf(pos1 to BoardSlot.Occupied(SkyjoCard.NumberCard(1, 1), faceUp = true)))),
                PlayerState(id = "p2", board = buildBoard(mapOf(pos2 to BoardSlot.Cleared))),
            ),
        )

        val exception = assertThrows(InvalidMoveException::class.java) {
            ActionCardEffect.PlayerSwap.apply(
                state,
                ActionCardParameters.PlayerSwap("p1", pos1, "p2", pos2),
            )
        }

        assertTrue(exception.message!!.contains("slot $pos2 of player p2 is not occupied"))
    }

    @Test
    fun playerSwapActionCardEffectLeavesOtherPlayersUnchanged() {
        val card1 = SkyjoCard.NumberCard(id = 10, value = 3)
        val card2 = SkyjoCard.NumberCard(id = 20, value = 7)
        val otherBoard = buildBoard()
        val pos1 = BoardPosition(0, 0)
        val pos2 = BoardPosition(1, 1)
        val state = GameState(
            players = listOf(
                PlayerState(id = "p1", board = buildBoard(mapOf(pos1 to BoardSlot.Occupied(card1, faceUp = true)))),
                PlayerState(id = "p2", board = buildBoard(mapOf(pos2 to BoardSlot.Occupied(card2, faceUp = false)))),
                PlayerState(id = "p3", board = otherBoard),
            ),
        )

        val result = ActionCardEffect.PlayerSwap.apply(
            state,
            ActionCardParameters.PlayerSwap("p1", pos1, "p2", pos2),
        )

        assertSame(otherBoard, result.players.first { it.id == "p3" }.board)
    }

    private fun buildBoard(overrides: Map<BoardPosition, BoardSlot> = emptyMap()): PlayerBoard {
        var idCounter = 1
        val slots = BoardLayout.POSITIONS.associateWith { pos ->
            overrides[pos] ?: BoardSlot.Occupied(
                card = SkyjoCard.NumberCard(id = idCounter++, value = 0),
                faceUp = false,
            )
        }
        return PlayerBoard(slots)
    }
}
