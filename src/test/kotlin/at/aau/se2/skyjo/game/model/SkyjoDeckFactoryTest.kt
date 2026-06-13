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

        assertEquals(18, actionCards.size)
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
    fun actionDrawPileContainsThreeDoubleTurnCards(){
        val drawPile = SkyjoDeckFactory.createShuffledActionDrawPile()

        assertEquals(3, drawPile.cards.count { it is SkyjoCard.ActionCard.DoubleTurn })
    }

    @Test
    fun actionDrawPileContainsThreeDrawThreeCards(){
        val drawPile = SkyjoDeckFactory.createShuffledActionDrawPile()

        assertEquals(3, drawPile.cards.count { it is SkyjoCard.ActionCard.DrawThreeCards })
    }

    @Test
    fun cardsHaveUniqueId(){
        val drawPile = SkyjoDeckFactory.createShuffledDrawPile()
        val actionDrawPile = SkyjoDeckFactory.createShuffledActionDrawPile()
        val ids = (drawPile.cards + actionDrawPile.cards).map{it.id}

        assertEquals(168, ids.toSet().size) //toSet entfehrnt Duplikate
        assertTrue(ids.all{it in 1..168})
    }

    @Test
    fun numberDrawPileContainsOnlyNumberCards(){
        val drawPile = SkyjoDeckFactory.createShuffledDrawPile()

        assertTrue(drawPile.cards.all { it is SkyjoCard.NumberCard })
    }

    @Test
    fun actionDrawPileContainsOnlyActionCards(){
        val drawPile = SkyjoDeckFactory.createShuffledActionDrawPile()

        assertEquals(18, drawPile.cards.size)
        assertTrue(drawPile.cards.all { it.id in 151..168 })
    }

    @Test
    fun actionDrawPileContainsThreeEnlightenmentCards(){
        val drawPile = SkyjoDeckFactory.createShuffledActionDrawPile()

        assertEquals(3, drawPile.cards.count { it is SkyjoCard.ActionCard.Enlightenment })
    }

    @Test
    fun actionDrawPileContainsNoPlaceholderCards(){
        val drawPile = SkyjoDeckFactory.createShuffledActionDrawPile()

        assertEquals(0, drawPile.cards.count { it is SkyjoCard.ActionCard.Placeholder })
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
