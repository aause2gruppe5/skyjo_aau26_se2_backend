package at.aau.se2.skyjo.game.model

import at.aau.se2.skyjo.game.error.InvalidMoveException
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class ActionCardEffectTest {

    // ── existing tests (keep as-is) ─────────────────────────────────────────

    @Test
    fun placeholderActionCardMapsToPlaceholderEffect() {
        val card = SkyjoCard.ActionCard.Placeholder(id = 1)
        assertSame(ActionCardEffect.Placeholder, card.toEffect())
    }

    @Test
    fun placeholderActionCardEffectKeepsStateUnchanged() {
        val state = GameState()
        assertSame(state, ActionCardEffect.Placeholder.apply(state, ActionCardParameters.None))
    }

    // ── PlayerSwapCard mapping ───────────────────────────────────────────────

    @Test
    fun `PlayerSwapCard maps to PlayerSwap effect`() {
        val card = SkyjoCard.ActionCard.PlayerSwapCard(id = 2)
        assertSame(ActionCardEffect.PlayerSwap, card.toEffect())
    }

    // ── PlayerSwap effect ────────────────────────────────────────────────────

    @Test
    fun `PlayerSwap swaps cards between two players`() {
        val card1 = SkyjoCard.NumberCard(id = 10, value = 3)
        val card2 = SkyjoCard.NumberCard(id = 20, value = 7)
        val pos1 = BoardPosition(0, 0)
        val pos2 = BoardPosition(1, 1)

        val board1 = buildBoard(mapOf(pos1 to BoardSlot.Occupied(card1, faceUp = true)))
        val board2 = buildBoard(mapOf(pos2 to BoardSlot.Occupied(card2, faceUp = false)))

        val state = GameState(
            players = listOf(
                PlayerState(id = "p1", board = board1),
                PlayerState(id = "p2", board = board2),
            )
        )

        val params = ActionCardParameters.PlayerSwap(
            player1Id = "p1", player1Position = pos1,
            player2Id = "p2", player2Position = pos2,
        )

        val result = ActionCardEffect.PlayerSwap.apply(state, params)

        val p1Slot = result.players.find { it.id == "p1" }!!.board.slotAt(pos1) as BoardSlot.Occupied
        val p2Slot = result.players.find { it.id == "p2" }!!.board.slotAt(pos2) as BoardSlot.Occupied

        assertEquals(card2, p1Slot.card)   // p1 now has card2
        assertEquals(card1, p2Slot.card)   // p2 now has card1
        assertTrue(p1Slot.faceUp)          // face-up state preserved from original slot1
        assertFalse(p2Slot.faceUp)         // face-up state preserved from original slot2
    }

    @Test
    fun `PlayerSwap works with face-down cards`() {
        val card1 = SkyjoCard.NumberCard(id = 10, value = 5)
        val card2 = SkyjoCard.NumberCard(id = 20, value = 9)
        val pos = BoardPosition(2, 3)

        val board1 = buildBoard(mapOf(pos to BoardSlot.Occupied(card1, faceUp = false)))
        val board2 = buildBoard(mapOf(pos to BoardSlot.Occupied(card2, faceUp = false)))

        val state = GameState(
            players = listOf(
                PlayerState(id = "p1", board = board1),
                PlayerState(id = "p2", board = board2),
            )
        )

        val params = ActionCardParameters.PlayerSwap("p1", pos, "p2", pos)
        val result = ActionCardEffect.PlayerSwap.apply(state, params)

        val p1Slot = result.players.find { it.id == "p1" }!!.board.slotAt(pos) as BoardSlot.Occupied
        val p2Slot = result.players.find { it.id == "p2" }!!.board.slotAt(pos) as BoardSlot.Occupied

        assertEquals(card2, p1Slot.card)
        assertEquals(card1, p2Slot.card)
    }

    @Test
    fun `PlayerSwap throws when both player IDs are the same`() {
        val pos = BoardPosition(0, 0)
        val board = buildBoard(mapOf(pos to BoardSlot.Occupied(SkyjoCard.NumberCard(1, 1), faceUp = true)))
        val state = GameState(players = listOf(PlayerState(id = "p1", board = board)))

        val params = ActionCardParameters.PlayerSwap("p1", pos, "p1", BoardPosition(0, 1))

        assertThrows<InvalidMoveException> {
            ActionCardEffect.PlayerSwap.apply(state, params)
        }
    }

    @Test
    fun `PlayerSwap throws when player1 not found`() {
        val pos = BoardPosition(0, 0)
        val board = buildBoard(mapOf(pos to BoardSlot.Occupied(SkyjoCard.NumberCard(1, 1), faceUp = true)))
        val state = GameState(players = listOf(PlayerState(id = "p2", board = board)))

        val params = ActionCardParameters.PlayerSwap("p1", pos, "p2", pos)

        assertThrows<InvalidMoveException> {
            ActionCardEffect.PlayerSwap.apply(state, params)
        }
    }

    @Test
    fun `PlayerSwap throws when player2 not found`() {
        val pos = BoardPosition(0, 0)
        val board = buildBoard(mapOf(pos to BoardSlot.Occupied(SkyjoCard.NumberCard(1, 1), faceUp = true)))
        val state = GameState(players = listOf(PlayerState(id = "p1", board = board)))

        val params = ActionCardParameters.PlayerSwap("p1", pos, "p2", pos)

        assertThrows<InvalidMoveException> {
            ActionCardEffect.PlayerSwap.apply(state, params)
        }
    }

    @Test
    fun `PlayerSwap throws when slot1 is cleared`() {
        val pos1 = BoardPosition(0, 0)
        val pos2 = BoardPosition(0, 1)
        val board1 = buildBoard(mapOf(pos1 to BoardSlot.Cleared))
        val board2 = buildBoard(mapOf(pos2 to BoardSlot.Occupied(SkyjoCard.NumberCard(1, 5), faceUp = true)))

        val state = GameState(
            players = listOf(
                PlayerState(id = "p1", board = board1),
                PlayerState(id = "p2", board = board2),
            )
        )

        assertThrows<InvalidMoveException> {
            ActionCardEffect.PlayerSwap.apply(state, ActionCardParameters.PlayerSwap("p1", pos1, "p2", pos2))
        }
    }

    @Test
    fun `PlayerSwap throws when slot2 is cleared`() {
        val pos1 = BoardPosition(0, 0)
        val pos2 = BoardPosition(0, 1)
        val board1 = buildBoard(mapOf(pos1 to BoardSlot.Occupied(SkyjoCard.NumberCard(1, 5), faceUp = true)))
        val board2 = buildBoard(mapOf(pos2 to BoardSlot.Cleared))

        val state = GameState(
            players = listOf(
                PlayerState(id = "p1", board = board1),
                PlayerState(id = "p2", board = board2),
            )
        )

        assertThrows<InvalidMoveException> {
            ActionCardEffect.PlayerSwap.apply(state, ActionCardParameters.PlayerSwap("p1", pos1, "p2", pos2))
        }
    }

    // ── helper ───────────────────────────────────────────────────────────────

    /**
     * Builds a full 3×4 PlayerBoard. Positions listed in [overrides] use the
     * provided slot; all others get a default face-down NumberCard.
     */
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
