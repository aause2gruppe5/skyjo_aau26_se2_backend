package at.aau.se2.skyjo.game.model

import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Test

class ActionCardEffectTest {
    @Test
    fun placeholderActionCardMapsToPlaceholderEffect(){
        val card = SkyjoCard.ActionCard.Placeholder(id = 1)

        assertSame(ActionCardEffect.Placeholder, card.toEffect())
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
}
