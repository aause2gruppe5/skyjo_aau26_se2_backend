package at.aau.se2.skyjo.game.model

sealed interface SkyjoCard {
    val id: Int

    sealed interface PlayingCard : SkyjoCard

    data class NumberCard(
        override val id: Int,
        val value: Int,
    ) : PlayingCard

    sealed interface ActionCard : SkyjoCard {
        data class Enlightenment(
            override val id: Int,
        ) : ActionCard

        data class Placeholder(
            override val id: Int,
        ) : ActionCard

        data class Defense(
            override val id: Int,
        ) : ActionCard

        data class SwapOwnCards(
            override val id: Int,
        ) : ActionCard

        data class PlayerSwapCard(
            override val id: Int,
        ) : ActionCard
    }
}

const val ACTION_CARD_SCORE: Int = 10

fun SkyjoCard.PlayingCard.scoreValue(): Int =
    when (this) {
        is SkyjoCard.NumberCard -> value
    }

fun SkyjoCard.ActionCard.scoreValue(): Int = ACTION_CARD_SCORE

fun SkyjoCard.displayLabel(): String =
    when (this) {
        is SkyjoCard.NumberCard -> value.toString()
        is SkyjoCard.ActionCard.Enlightenment -> "Enlightenment"
        is SkyjoCard.ActionCard.Defense -> "Defense"
        is SkyjoCard.ActionCard.SwapOwnCards -> "Swap Own Cards"
        is SkyjoCard.ActionCard.Placeholder -> "Action"
        is SkyjoCard.ActionCard.PlayerSwapCard -> "Swap"
    }
