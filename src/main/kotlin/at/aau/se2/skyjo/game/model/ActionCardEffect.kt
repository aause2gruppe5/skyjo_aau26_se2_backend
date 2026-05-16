package at.aau.se2.skyjo.game.model

import at.aau.se2.skyjo.game.error.InvalidMoveException

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

    data object PlayerSwap : ActionCardEffect {
        override fun apply(state: GameState, parameters: ActionCardParameters): GameState {
            require(parameters is ActionCardParameters.PlayerSwap) {
                "PlayerSwap effect requires PlayerSwap parameters"
            }

            if (parameters.player1Id == parameters.player2Id) {
                throw InvalidMoveException("cannot swap cards between the same player")
            }

            val p1 = state.players.find { it.id == parameters.player1Id }
                ?: throw InvalidMoveException("player ${parameters.player1Id} not found")
            val p2 = state.players.find { it.id == parameters.player2Id }
                ?: throw InvalidMoveException("player ${parameters.player2Id} not found")

            val slot1 = p1.board.slotAt(parameters.player1Position)
            if (slot1 !is BoardSlot.Occupied) {
                throw InvalidMoveException("slot ${parameters.player1Position} of player ${parameters.player1Id} is not occupied")
            }

            val slot2 = p2.board.slotAt(parameters.player2Position)
            if (slot2 !is BoardSlot.Occupied) {
                throw InvalidMoveException("slot ${parameters.player2Position} of player ${parameters.player2Id} is not occupied")
            }

            val updatedP1Board = p1.board.copy(
                slots = p1.board.slots + (parameters.player1Position to slot1.copy(card = slot2.card)),
            )
            val updatedP2Board = p2.board.copy(
                slots = p2.board.slots + (parameters.player2Position to slot2.copy(card = slot1.card)),
            )

            val updatedPlayers = state.players.map { player ->
                when (player.id) {
                    parameters.player1Id -> player.copy(board = updatedP1Board)
                    parameters.player2Id -> player.copy(board = updatedP2Board)
                    else -> player
                }
            }

            return state.copy(players = updatedPlayers)
        }
    }
}

fun SkyjoCard.ActionCard.toEffect(): ActionCardEffect =
    when (this) {
        is SkyjoCard.ActionCard.Defense -> ActionCardEffect.Defense
        is SkyjoCard.ActionCard.SwapOwnCards -> ActionCardEffect.SwapOwnCards
        is SkyjoCard.ActionCard.Placeholder -> ActionCardEffect.Placeholder
        is SkyjoCard.ActionCard.PlayerSwapCard -> ActionCardEffect.PlayerSwap
    }
