package at.aau.se2.skyjo.game.model

data class DiscardPile(
    val cards: List<SkyjoCard.PlayingCard>,
) {
    val size: Int
        get() = cards.size

    fun topCard(): SkyjoCard.PlayingCard {
        require(cards.isNotEmpty()) { "discard pile is empty" }
        return cards.last()
    }

    fun takeTop(): DiscardDrawResult {
        require(cards.isNotEmpty()) { "discard pile is empty" }
        return DiscardDrawResult(
            card = cards.last(),
            remainingPile = copy(cards = cards.dropLast(1)),
        )
    }

    fun add(card: SkyjoCard.PlayingCard): DiscardPile = copy(cards = cards + card)

    fun addAll(newCards: List<SkyjoCard.PlayingCard>): DiscardPile = copy(cards = cards + newCards)

    companion object {
        fun empty(): DiscardPile = DiscardPile(emptyList())
    }
}

data class DiscardDrawResult(
    val card: SkyjoCard.PlayingCard,
    val remainingPile: DiscardPile,
)

data class ActionDiscardPile(
    val cards: List<SkyjoCard.ActionCard>,
) {
    val size: Int
        get() = cards.size

    fun topCard(): SkyjoCard.ActionCard {
        require(cards.isNotEmpty()) { "action discard pile is empty" }
        return cards.last()
    }

    fun add(card: SkyjoCard.ActionCard): ActionDiscardPile = copy(cards = cards + card)

    companion object {
        fun empty(): ActionDiscardPile = ActionDiscardPile(emptyList())
    }
}
