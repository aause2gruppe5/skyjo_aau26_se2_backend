package at.aau.se2.skyjo.model

import at.aau.se2.skyjo.game.model.GamePhase
import at.aau.se2.skyjo.game.model.RoundResult

data class GameUpdateMessage(
    val phase: GamePhase,
    val currentPlayerId: String?,
    val players: List<PlayerBoardDto>,
    val discardTopCard: CardDto?,
    val drawnCard: CardDto?,
    val roundResult: RoundResult?,
    val roundNumber: Int,
    val totalScores: List<PlayerScoreDto>,
    val gameOver: Boolean,
    val gameId: String? = null,
    val disconnectedPlayers: List<String> = emptyList(),
)

data class PlayerBoardDto(
    val playerId: String,
    val nickname: String,
    val board: List<List<BoardSlotDto>>,
)

data class BoardSlotDto(
    val type: SlotType,
    val faceUp: Boolean?,
    val card: CardDto?,
)

enum class SlotType { OCCUPIED, CLEARED }

data class CardDto(
    val id: Int,
    val value: Int?,
    val type: CardType,
)

enum class CardType { NUMBER, ACTION }

data class PlayerScoreDto(
    val playerId: String,
    val nickname: String,
    val totalScore: Int,
)
