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
    fun placeholderActionCardEffectKeepsStateUnchanged(){
        val state = GameState()

        assertSame(state, ActionCardEffect.Placeholder.apply(state, ActionCardParameters.None))
    }

    @Test
    fun enlightenmentActionCardMapsToEnlightenmentEffect(){
        val card = SkyjoCard.ActionCard.Enlightenment(id = 1)

        assertSame(ActionCardEffect.Enlightenment, card.toEffect())
    }

    @Test
    fun defenseActionCardEffectAddsPendingExtraTurn(){
        val state = GameState(pendingExtraTurns = 1)

        val result = ActionCardEffect.Defense.apply(state, ActionCardParameters.None)

        assertSame(state.players, result.players)
        assertEquals(2, result.pendingExtraTurns)
    }
}
