package at.aau.se2.skyjo.game.model

sealed interface ActionCardEffect {
    fun apply(state: GameState, parameters: ActionCardParameters): GameState

    data object Placeholder : ActionCardEffect {
        override fun apply(state: GameState, parameters: ActionCardParameters): GameState = state
    }

    data object Defense : ActionCardEffect {
        override fun apply(state: GameState, parameters: ActionCardParameters): GameState =
            state.copy(pendingExtraTurns = state.pendingExtraTurns + 1)
    }
}

fun SkyjoCard.ActionCard.toEffect(): ActionCardEffect =
    when (this) {
        is SkyjoCard.ActionCard.Defense -> ActionCardEffect.Defense
        is SkyjoCard.ActionCard.Placeholder -> ActionCardEffect.Placeholder
    }
