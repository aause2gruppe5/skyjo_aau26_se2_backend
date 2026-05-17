package at.aau.se2.skyjo.game.model
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*

class SkyjoDeckFactoryTest {
    @Test
    fun cardDistributionMakes150NumberCards(){
        val drawPile = SkyjoDeckFactory.createShuffledDrawPile()

        assertEquals(150, drawPile.cards.size)
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
        val drawPile = SkyjoDeckFactory.createShuffledActionDrawPile()
        val actionCards = drawPile.cards.filterIsInstance<SkyjoCard.ActionCard>()

        assertEquals(21, actionCards.size)
    }

    @Test
    fun actionDrawPileContainsThreeDefenseCards(){
        val drawPile = SkyjoDeckFactory.createShuffledActionDrawPile()

        assertEquals(3, drawPile.cards.count { it is SkyjoCard.ActionCard.Defense })
    }

    @Test
    fun actionDrawPileContainsThreePlayerSwapCards(){
        val drawPile = SkyjoDeckFactory.createShuffledActionDrawPile()

        assertEquals(3, drawPile.cards.count { it is SkyjoCard.ActionCard.PlayerSwapCard })
    }

    @Test
    fun actionDrawPileContainsThreeSwapOwnCards(){
        val drawPile = SkyjoDeckFactory.createShuffledActionDrawPile()

        assertEquals(3, drawPile.cards.count { it is SkyjoCard.ActionCard.SwapOwnCards })
    }

    @Test
    fun cardsHaveUniqueId(){
        val drawPile = SkyjoDeckFactory.createShuffledDrawPile()
        val actionDrawPile = SkyjoDeckFactory.createShuffledActionDrawPile()
        val ids = (drawPile.cards + actionDrawPile.cards).map{it.id}

        assertEquals(171, ids.toSet().size) //toSet entfehrnt Duplikate
        assertTrue(ids.all{it in 1..171})
    }

    @Test
    fun numberDrawPileContainsOnlyNumberCards(){
        val drawPile = SkyjoDeckFactory.createShuffledDrawPile()

        assertTrue(drawPile.cards.all { it is SkyjoCard.NumberCard })
    }

    @Test
    fun actionDrawPileContainsOnlyActionCards(){
        val drawPile = SkyjoDeckFactory.createShuffledActionDrawPile()

        assertEquals(21, drawPile.cards.size)
        assertTrue(drawPile.cards.all { it.id in 151..171 })
    }

    @Test
    fun actionDrawPileContainsThreeEnlightenmentCards(){
        val drawPile = SkyjoDeckFactory.createShuffledActionDrawPile()

        assertEquals(3, drawPile.cards.count { it is SkyjoCard.ActionCard.Enlightenment })
        assertEquals(9, drawPile.cards.count { it is SkyjoCard.ActionCard.Placeholder })
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
