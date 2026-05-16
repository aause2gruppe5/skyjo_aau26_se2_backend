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

    data object SwapOwnCards : ActionCardEffect {
        override fun apply(state: GameState, parameters: ActionCardParameters): GameState {
            if (parameters !is ActionCardParameters.SwapOwnParameters) return state

            val currentPlayer = state.currentPlayer()
            val board = currentPlayer.board
            val slot1 = board.slotAt(parameters.pos1) as? BoardSlot.Occupied ?: return state
            val slot2 = board.slotAt(parameters.pos2) as? BoardSlot.Occupied ?: return state

            val newSlots = board.slots.toMutableMap()
            newSlots[parameters.pos1] = BoardSlot.Occupied(slot2.card, parameters.faceUp1)
            newSlots[parameters.pos2] = BoardSlot.Occupied(slot1.card, parameters.faceUp2)

            val updatedBoard = board.copy(slots = newSlots)
            val cleanupResult = updatedBoard.clearCompletedLines()

            val updatedPlayer = currentPlayer.copy(board = cleanupResult.board)
            val updatedPlayers = state.players.mapIndexed { index, player ->
                if (index == state.currentPlayerIndex) updatedPlayer else player
            }

            return state.copy(
                players = updatedPlayers,
                discardPile = state.discardPile.addAll(cleanupResult.removedCards),
            )
        }
    }
}

fun SkyjoCard.ActionCard.toEffect(): ActionCardEffect =
    when (this) {
        is SkyjoCard.ActionCard.Defense -> ActionCardEffect.Defense
        is SkyjoCard.ActionCard.SwapOwnCards -> ActionCardEffect.SwapOwnCards
        is SkyjoCard.ActionCard.Placeholder -> ActionCardEffect.Placeholder
    }
