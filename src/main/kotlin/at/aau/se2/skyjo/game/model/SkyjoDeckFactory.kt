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

    private const val NUMBER_CARD_COUNT = 150
    private const val ACTION_CARD_COUNT = 10

    fun createNumberDrawPile(seed: Long? = null): DrawPile {
        val random = seed?.let { Random(it) } ?: Random.Default
        val cards = cardDistribution
            .mapIndexed { index, value -> SkyjoCard.NumberCard(id = index + 1, value = value) }
            .shuffled(random)
        return DrawPile(cards)
    }

    fun createActionDrawPile(seed: Long? = null): DrawPile {
        val random = seed?.let { Random(it) } ?: Random.Default
        val cards = List(ACTION_CARD_COUNT) { index ->
            SkyjoCard.ActionCard.Placeholder(id = NUMBER_CARD_COUNT + index + 1)
        }.shuffled(random)
        return DrawPile(cards)
    }
}
