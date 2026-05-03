package at.aau.se2.skyjo.game.model

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class SkyjoCardTest {
    @Test
    fun numberCardScoreValueIsItsValue(){
        val card = SkyjoCard.NumberCard(id = 1, value = -2)

        assertEquals(-2, card.scoreValue())
    }

    @Test
    fun actionCardScoreValueIsZero(){
        val card = SkyjoCard.ActionCard.Placeholder(id = 2)

        assertEquals(ACTION_CARD_SCORE, card.scoreValue())
    }
}
