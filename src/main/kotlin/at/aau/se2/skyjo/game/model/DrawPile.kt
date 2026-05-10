package at.aau.se2.skyjo.game.model

data class DrawPile(
    val cards: List<SkyjoCard.PlayingCard>,
) {
    val size: Int
        get() = cards.size

    fun draw(): DrawResult {
        require(cards.isNotEmpty()) { "draw pile is empty" }
        return DrawResult(
            card = cards.last(),
            remainingPile = copy(cards = cards.dropLast(1)),
        )
    }

    companion object {
        fun empty(): DrawPile = DrawPile(emptyList())
    }
}

data class DrawResult(
    val card: SkyjoCard.PlayingCard,
    val remainingPile: DrawPile,
)

data class ActionDrawPile(
    val cards: List<SkyjoCard.ActionCard>,
) {
    val size: Int
        get() = cards.size

    fun draw(): ActionDrawResult {
        require(cards.isNotEmpty()) { "action draw pile is empty" }
        return ActionDrawResult(
            card = cards.last(),
            remainingPile = copy(cards = cards.dropLast(1)),
        )
    }

    companion object {
        fun empty(): ActionDrawPile = ActionDrawPile(emptyList())
    }
}

data class ActionDrawResult(
    val card: SkyjoCard.ActionCard,
    val remainingPile: ActionDrawPile,
)
