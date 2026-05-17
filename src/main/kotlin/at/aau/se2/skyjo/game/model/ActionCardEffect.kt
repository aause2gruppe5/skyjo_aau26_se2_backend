package at.aau.se2.skyjo.game.model

import at.aau.se2.skyjo.game.error.InvalidMoveException

sealed interface ActionCardEffect {
    fun apply(state: GameState, parameters: ActionCardParameters): GameState

    data object Placeholder : ActionCardEffect {
        override fun apply(state: GameState, parameters: ActionCardParameters): GameState = state
    }

    data object Enlightenment : ActionCardEffect {
        override fun apply(state: GameState, parameters: ActionCardParameters): GameState {
            val target = parameters as? ActionCardParameters.BoardLineTarget
                ?: throw InvalidMoveException("enlightenment requires a board row or column target")
            val actingPlayerId = state.currentPlayerId
                ?: throw InvalidMoveException("current player is not available")
            val targetPlayer = state.players.firstOrNull { it.id == target.targetPlayerId }
                ?: throw InvalidMoveException("target player ${target.targetPlayerId} is not available")
            val targetPositions = target.positions()
            val viewedCards = targetPositions.mapNotNull { position ->
                when (val slot = targetPlayer.board.slotAt(position)) {
                    is BoardSlot.Cleared -> null
                    is BoardSlot.Occupied -> if (slot.faceUp) null else ViewedCard(position, slot.card)
                }
            }

            return state.copy(
                actionCardResult = ActionCardResult.Enlightenment(
                    actingPlayerId = actingPlayerId,
                    targetPlayerId = targetPlayer.id,
                    targetType = target.targetType,
                    lineIndex = target.lineIndex,
                    cards = viewedCards,
                ),
            )
        }
    }

    data object Defense : ActionCardEffect {
        override fun apply(state: GameState, parameters: ActionCardParameters): GameState =
            state.copy(pendingExtraTurns = state.pendingExtraTurns + 1)
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
        is SkyjoCard.ActionCard.Enlightenment -> ActionCardEffect.Enlightenment
        is SkyjoCard.ActionCard.Defense -> ActionCardEffect.Defense
        is SkyjoCard.ActionCard.Placeholder -> ActionCardEffect.Placeholder
        is SkyjoCard.ActionCard.PlayerSwapCard -> ActionCardEffect.PlayerSwap
    }

private fun ActionCardParameters.BoardLineTarget.positions(): List<BoardPosition> =
    when (targetType) {
        BoardLineTargetType.ROW -> {
            if (lineIndex !in 0 until BoardLayout.ROWS) {
                throw InvalidMoveException("row index $lineIndex is not available")
            }
            BoardLayout.HORIZONTAL_LINES[lineIndex]
        }
        BoardLineTargetType.COLUMN -> {
            if (lineIndex !in 0 until BoardLayout.COLUMNS) {
                throw InvalidMoveException("column index $lineIndex is not available")
            }
            BoardLayout.VERTICAL_LINES[lineIndex]
        }
    }
