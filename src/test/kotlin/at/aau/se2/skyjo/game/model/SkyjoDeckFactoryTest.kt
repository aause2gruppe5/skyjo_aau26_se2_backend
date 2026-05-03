package at.aau.se2.skyjo.game.model
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*

class SkyjoDeckFactoryTest {
    @Test
    fun numberDrawPileHas150Cards(){
        val pile = SkyjoDeckFactory.createNumberDrawPile()

        assertEquals(150, pile.cards.size)
    }

    @Test
    fun actionDrawPileHas21Cards(){
        val pile = SkyjoDeckFactory.createActionDrawPile()

        assertEquals(21, pile.cards.size)
    }

    @Test
    fun numberCardDistributionIsCorrect(){
        val pile = SkyjoDeckFactory.createNumberDrawPile()
        val counts = pile.cards.filterIsInstance<SkyjoCard.NumberCard>().groupingBy { it.scoreValue() }.eachCount()

        assertEquals(150, pile.cards.size)
        assertEquals(5, counts[-2])
        assertEquals(10, counts[-1])
        assertEquals(15, counts[0])
        for (value in 1..12){ assertEquals(10, counts[value]) }
    }

    @Test
    fun actionDrawPileContainsOnlyActionCards(){
        val pile = SkyjoDeckFactory.createActionDrawPile()

        assertTrue(pile.cards.all { it is SkyjoCard.ActionCard })
    }

    @Test
    fun numberPileCardsHaveUniqueId(){
        val pile = SkyjoDeckFactory.createNumberDrawPile()
        val ids = pile.cards.map { it.id }

        assertEquals(150, ids.toSet().size)
        assertTrue(ids.all { it in 1..150 })
    }

    @Test
    fun actionPileCardsHaveUniqueId(){
        val pile = SkyjoDeckFactory.createActionDrawPile()
        val ids = pile.cards.map { it.id }

        assertEquals(21, ids.toSet().size)
        assertTrue(ids.all { it in 151..171 })
    }

    @Test
    fun numberPileShuffleWithSameSeed(){
        val seed = 42L
        val pile1 = SkyjoDeckFactory.createNumberDrawPile(seed)
        val pile2 = SkyjoDeckFactory.createNumberDrawPile(seed)

        assertEquals(pile1.cards, pile2.cards)
    }

    @Test
    fun actionPileShuffleWithSameSeed(){
        val seed = 42L
        val pile1 = SkyjoDeckFactory.createActionDrawPile(seed)
        val pile2 = SkyjoDeckFactory.createActionDrawPile(seed)

        assertEquals(pile1.cards, pile2.cards)
    }

    @Test
    fun numberPileShuffleWithDifferentSeed(){
        val pile1 = SkyjoDeckFactory.createNumberDrawPile(123L)
        val pile2 = SkyjoDeckFactory.createNumberDrawPile(456L)

        assertNotEquals(pile1.cards, pile2.cards)
    }

    @Test
    fun numberPileShuffleWithoutSeed(){
        val pile1 = SkyjoDeckFactory.createNumberDrawPile()
        val pile2 = SkyjoDeckFactory.createNumberDrawPile()

        assertNotEquals(pile1.cards, pile2.cards)
    }
}
