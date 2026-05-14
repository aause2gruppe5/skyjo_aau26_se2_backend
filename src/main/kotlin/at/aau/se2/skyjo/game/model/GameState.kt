package at.aau.se2.skyjo.game.model

import at.aau.se2.skyjo.game.error.InvalidMoveException

data class GameState(
    val players: List<PlayerState> = emptyList(),
    val currentPlayerIndex: Int = 0,
    val drawPile: DrawPile = DrawPile.empty(),
    val discardPile: DiscardPile = DiscardPile.empty(),
    val actionDrawPile: ActionDrawPile = ActionDrawPile.empty(),
    val visibleActionCards: List<SkyjoCard.ActionCard> = emptyList(),
    val actionDiscardPile: ActionDiscardPile = ActionDiscardPile.empty(),
    val phase: GamePhase = GamePhase.NOT_STARTED,
    val drawnCard: SkyjoCard.PlayingCard? = null,
    val drawSource: DrawSource? = null,
    val finisherPlayerId: String? = null,
    val finalTurnsRemaining: Int = 0,
    val roundResult: RoundResult? = null,
    val shuffleSeed: Long? = null,
    val shuffleCount: Int = 0,
    val pendingExtraTurns: Int = 0,
) {
    val currentPlayerId: String?
        get() = players.getOrNull(currentPlayerIndex)?.id

    fun currentPlayer(): PlayerState = players[currentPlayerIndex]

    fun consumeDefenseForAttack(targetPlayerIndex: Int): AttackProtectionResult {
        if (targetPlayerIndex !in players.indices) {
            throw InvalidMoveException("target player index $targetPlayerIndex is not available")
        }

        val targetPlayer = players[targetPlayerIndex]
        val defenseIndex = targetPlayer.actionCards.indexOfFirst { it is SkyjoCard.ActionCard.Defense }
        if (defenseIndex == -1) {
            return AttackProtectionResult(state = this, blocked = false)
        }

        val defenseCard = targetPlayer.actionCards[defenseIndex]
        val updatedTargetPlayer = targetPlayer.copy(
            actionCards = targetPlayer.actionCards.filterIndexed { index, _ -> index != defenseIndex },
        )
        val updatedPlayers = players.mapIndexed { index, player ->
            if (index == targetPlayerIndex) updatedTargetPlayer else player
        }

        return AttackProtectionResult(
            state = copy(
                players = updatedPlayers,
                actionDiscardPile = actionDiscardPile.add(defenseCard),
            ),
            blocked = true,
        )
    }
}

data class AttackProtectionResult(
    val state: GameState,
    val blocked: Boolean,
)
