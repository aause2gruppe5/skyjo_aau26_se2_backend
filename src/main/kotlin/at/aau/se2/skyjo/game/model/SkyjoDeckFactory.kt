package at.aau.se2.skyjo.game.model

import kotlin.random.Random

object SkyjoDeckFactory {

    private val cardDistribution: List<Int> = buildList {
        addAll(List(size = 5) { -2 })
        addAll(List(size = 10) { -1 })
        addAll(List(size = 15) { 0 })
        for (value in 1..12) {
            addAll(List(size = 10) { value })
        }
    }

    private const val ACTION_CARD_COUNT = 10

    fun createShuffledDrawPile(seed: Long? = null): DrawPile {
        val random = seed?.let { Random(it) } ?: Random.Default

        val numberCards = cardDistribution
            .mapIndexed { index, value ->
                SkyjoCard.NumberCard(id = index + 1, value = value)
            }

        val actionCards = List(ACTION_CARD_COUNT) { index ->
            SkyjoCard.ActionCard.Placeholder(id = numberCards.size + index + 1)
        }

        val cards = (numberCards + actionCards).shuffled(random)
        return DrawPile(cards)
    }
}
