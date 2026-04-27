package at.aau.se2.skyjo.game.model

sealed interface ActionCardEffect {

    fun apply(state: GameState): GameState

    data object Placeholder : ActionCardEffect {
        override fun apply(state: GameState): GameState = state
    }
}

fun SkyjoCard.ActionCard.toEffect(): ActionCardEffect =
    when (this) {
        is SkyjoCard.ActionCard.Placeholder -> ActionCardEffect.Placeholder
    }
