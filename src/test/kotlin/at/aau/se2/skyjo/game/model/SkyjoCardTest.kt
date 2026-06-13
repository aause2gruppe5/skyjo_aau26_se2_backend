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
    fun placeholderScoreValueUsesActionCardScore(){
        val card = SkyjoCard.ActionCard.Placeholder(id = 151)

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
    fun swapOwnCardsScoreValueIsActionCardScore(){
        val card = SkyjoCard.ActionCard.SwapOwnCards(id = 153)

        assertEquals(ACTION_CARD_SCORE, card.scoreValue())
    }

    @Test
    fun doubleTurnCardScoreValueIsActionCardScore(){
        val card = SkyjoCard.ActionCard.DoubleTurn(id = 154)

        assertEquals(ACTION_CARD_SCORE, card.scoreValue())
    }

    @Test
    fun drawThreeCardsScoreValueIsActionCardScore(){
        val card = SkyjoCard.ActionCard.DrawThreeCards(id = 155)

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

    @Test
    fun placeholderDisplayLabelIsAction(){
        val card = SkyjoCard.ActionCard.Placeholder(id = 151)

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

    @Test
    fun swapOwnCardsDisplayLabelIsSwapOwnCards(){
        val card = SkyjoCard.ActionCard.SwapOwnCards(id = 153)

        assertEquals("Swap Own Cards", card.displayLabel())
    }

    @Test
    fun doubleTurnCardDisplayLabelIsDoubleTurn(){
        val card = SkyjoCard.ActionCard.DoubleTurn(id = 154)

        assertEquals("DoubleTurn", card.displayLabel())
    }

    @Test
    fun drawThreeCardsDisplayLabelIsDrawThreeCards(){
        val card = SkyjoCard.ActionCard.DrawThreeCards(id = 155)

        assertEquals("Draw Three Cards", card.displayLabel())
    }
}
