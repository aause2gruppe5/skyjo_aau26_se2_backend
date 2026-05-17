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

    private const val ACTION_CARD_COUNT = 21
    private const val ENLIGHTENMENT_CARD_COUNT = 3
    private const val DEFENSE_CARD_COUNT = 3
    private const val PLAYER_SWAP_CARD_COUNT = 3

    fun createShuffledDrawPile(seed: Long? = null): DrawPile {
        val random = seed?.let { Random(it) } ?: Random.Default
        val cards = cardDistribution
            .mapIndexed { index, value ->
                SkyjoCard.NumberCard(id = index + 1, value = value)
            }
            .shuffled(random)
        return DrawPile(cards)
    }

    fun createShuffledActionDrawPile(seed: Long? = null): ActionDrawPile {
        val random = seed?.let { Random(it + 1L) } ?: Random.Default
        val numberCardCount = cardDistribution.size
        val actionCards = List(ACTION_CARD_COUNT) { index ->
            val id = numberCardCount + index + 1
            when {
                index < DEFENSE_CARD_COUNT -> SkyjoCard.ActionCard.Defense(id)
                index < DEFENSE_CARD_COUNT + ENLIGHTENMENT_CARD_COUNT -> SkyjoCard.ActionCard.Enlightenment(id)
                index < DEFENSE_CARD_COUNT + ENLIGHTENMENT_CARD_COUNT + PLAYER_SWAP_CARD_COUNT ->
                    SkyjoCard.ActionCard.PlayerSwapCard(id)
                else -> SkyjoCard.ActionCard.Placeholder(id)
            }
        }.shuffled(random)

        return ActionDrawPile(actionCards)
    }
}
