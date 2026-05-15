package at.aau.se2.skyjo.game.model

import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Test

class ActionCardEffectTest {
    @Test
    fun enlightenmentActionCardMapsToEnlightenmentEffect(){
        val card = SkyjoCard.ActionCard.Enlightenment(id = 1)

        assertSame(ActionCardEffect.Enlightenment, card.toEffect())
    }
}
