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

    @Test
    fun defenseCardScoreValueIsActionCardScore(){
        val card = SkyjoCard.ActionCard.Defense(id = 151)

        assertEquals(ACTION_CARD_SCORE, card.scoreValue())
    }

    @Test
    fun playerSwapCardScoreValueIsActionCardScore(){
        val card = SkyjoCard.ActionCard.PlayerSwapCard(id = 152)

        assertEquals(ACTION_CARD_SCORE, card.scoreValue())
    }

    @Test
    fun numberCardDisplayLabelIsValue(){
        val card = SkyjoCard.NumberCard(id = 1, value = 12)

        assertEquals("12", card.displayLabel())
    }

    @Test
    fun actionCardDisplayLabelIsAction(){
        val card = SkyjoCard.ActionCard.Placeholder(id = 2)

        assertEquals("Action", card.displayLabel())
    }

    @Test
    fun defenseCardDisplayLabelIsDefense(){
        val card = SkyjoCard.ActionCard.Defense(id = 151)

        assertEquals("Defense", card.displayLabel())
    }

    @Test
    fun playerSwapCardDisplayLabelIsSwap(){
        val card = SkyjoCard.ActionCard.PlayerSwapCard(id = 152)

        assertEquals("Swap", card.displayLabel())
    }
}
