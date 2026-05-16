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
}

fun SkyjoCard.ActionCard.toEffect(): ActionCardEffect =
    when (this) {
        is SkyjoCard.ActionCard.Enlightenment -> ActionCardEffect.Enlightenment
        is SkyjoCard.ActionCard.Defense -> ActionCardEffect.Defense
        is SkyjoCard.ActionCard.Placeholder -> ActionCardEffect.Placeholder
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
