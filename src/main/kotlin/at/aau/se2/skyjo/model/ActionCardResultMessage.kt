package at.aau.se2.skyjo.model

import at.aau.se2.skyjo.game.model.BoardLineTargetType

data class ActionCardResultMessage(
    val type: ActionCardResultType,
    val actionCardIndex: Int,
    val targetPlayerId: String? = null,
    val targetType: BoardLineTargetType? = null,
    val lineIndex: Int? = null,
    val inspectedValues: List<Int?> = emptyList(),
    val inspectedCards: List<InspectedCardDto> = emptyList(),
    val drawnCards: List<CardDto> = emptyList(),
)

enum class ActionCardResultType {
    ENLIGHTENMENT,
    DRAW_THREE_CARDS,
}

data class InspectedCardDto(
    val row: Int,
    val col: Int,
    val value: Int?,
    val card: CardDto?,
)

data class PlayActionCardMessageResult(
    val gameUpdate: GameUpdateMessage,
    val privateActionCardResults: Map<String, ActionCardResultMessage> = emptyMap(),
)
