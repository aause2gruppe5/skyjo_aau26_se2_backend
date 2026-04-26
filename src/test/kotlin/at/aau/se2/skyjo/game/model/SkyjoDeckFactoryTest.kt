package at.aau.se2.skyjo.game.model
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*

class SkyjoDeckFactoryTest {
    @Test
    fun cardDistributionMakes160Cards(){
        val drawPile = SkyjoDeckFactory.createShuffledDrawPile()

        assertEquals(160, drawPile.cards.size)
    }

    @Test
    fun cardDistributionIsCorrect(){
        val drawPile = SkyjoDeckFactory.createShuffledDrawPile()
        val cards = drawPile.cards.filterIsInstance<SkyjoCard.NumberCard>()
        val counts = cards.groupingBy { it.scoreValue() }.eachCount()

        assertEquals(150, cards.size)
        assertEquals(5, counts[-2])
        assertEquals(10, counts[-1])
        assertEquals(15, counts[0])
        for (value in 1..12){assertEquals(10, counts[value])}
    }

    @Test
    fun cardDistributionContainsActionCards(){
        val drawPile = SkyjoDeckFactory.createShuffledDrawPile()
        val actionCards = drawPile.cards.filterIsInstance<SkyjoCard.ActionCard>()

        assertEquals(10, actionCards.size)
    }

    @Test
    fun cardsHaveUniqueId(){
        val drawPile = SkyjoDeckFactory.createShuffledDrawPile()
        val ids = drawPile.cards.map{it.id}

        assertEquals(160, ids.toSet().size) //toSet entfehrnt Duplikate
        assertTrue(ids.all{it in 1..160})
    }

    @Test
    fun shuffleWithSameSeed(){
        val seed = 42L
        val pile1 = SkyjoDeckFactory.createShuffledDrawPile(seed)
        val pile2 = SkyjoDeckFactory.createShuffledDrawPile(seed)

        assertEquals(pile1.cards, pile2.cards)
    }

    @Test
    fun shuffleWithDifferentSeed(){
        val pile1 = SkyjoDeckFactory.createShuffledDrawPile(123L)
        val pile2 = SkyjoDeckFactory.createShuffledDrawPile(456L)

        assertNotEquals(pile1.cards, pile2.cards)
    }

    @Test
    fun shuffleWithoutSeed(){
        val pile1 = SkyjoDeckFactory.createShuffledDrawPile()
        val pile2 = SkyjoDeckFactory.createShuffledDrawPile()

        assertNotEquals(pile1.cards, pile2.cards)
    }
}
