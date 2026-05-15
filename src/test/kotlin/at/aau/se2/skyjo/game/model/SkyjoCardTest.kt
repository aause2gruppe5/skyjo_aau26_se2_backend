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
    fun actionCardScoreValueUsesActionCardScore(){
        val card = SkyjoCard.ActionCard.Enlightenment(id = 2)

        assertEquals(ACTION_CARD_SCORE, card.scoreValue())
    }

    @Test
    fun numberCardDisplayLabelIsValue(){
        val card = SkyjoCard.NumberCard(id = 1, value = 12)

        assertEquals("12", card.displayLabel())
    }

    @Test
    fun enlightenmentDisplayLabelIsEnlightenment(){
        val card = SkyjoCard.ActionCard.Enlightenment(id = 2)

        assertEquals("Enlightenment", card.displayLabel())
    }
}
