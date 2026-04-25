package at.aau.se2.skyjo.game.model

sealed interface SkyjoCard {
    val id: Int

    data class NumberCard(
        override val id: Int,
        val value: Int,
    ) : SkyjoCard

    sealed interface ActionCard : SkyjoCard {
        data class Placeholder(
            override val id: Int,
        ) : ActionCard
    }
}
fun SkyjoCard.scoreValue(): Int =
    when (this) {
        is SkyjoCard.NumberCard -> value
        is SkyjoCard.ActionCard -> 0
    }
