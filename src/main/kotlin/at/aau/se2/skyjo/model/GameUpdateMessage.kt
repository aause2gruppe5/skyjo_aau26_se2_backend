package at.aau.se2.skyjo.model

import at.aau.se2.skyjo.game.model.ACTION_CARD_SCORE
import at.aau.se2.skyjo.game.model.GamePhase
import at.aau.se2.skyjo.game.model.RoundResult

data class GameUpdateMessage(
    val phase: GamePhase,
    val currentPlayerId: String?,
    val players: List<PlayerBoardDto>,
    val discardTopCard: CardDto?,
    val drawnCard: CardDto?,
    val visibleActionCards: List<ActionCardDto> = emptyList(),
    val actionDrawPileCount: Int = 0,
    val roundResult: RoundResult?,
    val roundNumber: Int,
    val totalScores: List<PlayerScoreDto>,
    val gameOver: Boolean,
    val gameId: String? = null,
    val lobbyId: String? = null,
    val disconnectedPlayers: List<String> = emptyList(),
    val turnId: Int = 0,
)

data class PlayerBoardDto(
    val playerId: String,
    val nickname: String,
    val board: List<List<BoardSlotDto>>,
    val actionCards: List<ActionCardDto> = emptyList(),
    val remainingCheatReports: Int = 3,
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

data class ActionCardDto(
    val id: Int,
    val kind: ActionCardKind,
    val label: String = kind.label,
    val value: Int = ACTION_CARD_SCORE,
)

enum class ActionCardKind(val label: String) {
    PLACEHOLDER("Action"),
    DEFENSE("Defense"),
    ENLIGHTENMENT("Enlightenment"),
    SWAP_OWN_CARDS("Swap Own Cards"),
    PLAYER_SWAP("Swap"),
    DOUBLE_TURN("DoubleTurn"),
    DRAW_THREE_CARDS("Draw Three Cards"),
}

data class PlayerScoreDto(
    val playerId: String,
    val nickname: String,
    val totalScore: Int,
)

data class CheatPeekResultMessage(
    val card: CardDto,
    val remainingCheatPeeks: Int,
)

data class CheatReportResultMessage(
    val successful: Boolean,
    val reporterPlayerId: String,
    val targetPlayerId: String,
    val penaltyPlayerId: String,
    val penaltyPoints: Int,
    val remainingCheatReports: Int,
)

data class CheatReportMessageResult(
    val gameUpdate: GameUpdateMessage,
    val privateReportResult: CheatReportResultMessage,
)
