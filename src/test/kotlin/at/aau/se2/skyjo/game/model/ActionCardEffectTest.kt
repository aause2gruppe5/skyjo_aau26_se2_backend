package at.aau.se2.skyjo.game.model

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertSame
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
    fun swapOwnCardsActionCardMapsToSwapOwnCardsEffect(){
        val card = SkyjoCard.ActionCard.SwapOwnCards(id = 200)

        assertSame(ActionCardEffect.SwapOwnCards, card.toEffect())
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
    fun swapOwnCardsEffectSwapsTwoCardsAndSetsFaceUpStatus() {
        val player1Id = "player1"
        val card1 = SkyjoCard.NumberCard(1, 1)
        val card2 = SkyjoCard.NumberCard(2, 2)
        val card3 = SkyjoCard.NumberCard(3, 3)
        val card4 = SkyjoCard.NumberCard(4, 4)

        val initialCards = listOf(card1, card2, card3, card4) + List(8) { SkyjoCard.NumberCard(it + 5, 5) }
        val playerBoard = PlayerBoard.fromCards(initialCards, setOf(BoardPosition(0, 0), BoardPosition(1, 1)))
        val playerState = PlayerState(player1Id, playerBoard)
        val state = GameState(players = listOf(playerState))

        val pos1 = BoardPosition(0, 0)
        val pos2 = BoardPosition(0, 1)

        val parameters = ActionCardParameters.SwapOwnParameters(pos1, false, pos2, true)
        val result = ActionCardEffect.SwapOwnCards.apply(state, parameters)

        val updatedPlayerBoard = result.players[0].board
        assertEquals(BoardSlot.Occupied(card2, false), updatedPlayerBoard.slotAt(pos1))
        assertEquals(BoardSlot.Occupied(card1, true), updatedPlayerBoard.slotAt(pos2))
    }

    @Test
    fun swapOwnCardsEffectDoesNothingIfParametersAreNotSwapOwnParameters() {
        val player1Id = "player1"
        val card1 = SkyjoCard.NumberCard(1, 1)
        val card2 = SkyjoCard.NumberCard(2, 2)

        val initialCards = listOf(card1, card2) + List(10) { SkyjoCard.NumberCard(it + 3, 3) }
        val playerBoard = PlayerBoard.fromCards(initialCards, setOf(BoardPosition(0, 0), BoardPosition(1, 1)))
        val playerState = PlayerState(player1Id, playerBoard)
        val state = GameState(players = listOf(playerState))

        val result = ActionCardEffect.SwapOwnCards.apply(state, ActionCardParameters.None)

        assertSame(state, result)
    }
}
