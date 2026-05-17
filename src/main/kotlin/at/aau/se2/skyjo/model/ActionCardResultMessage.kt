package at.aau.se2.skyjo.model

import at.aau.se2.skyjo.game.model.BoardLineTargetType

data class ActionCardResultMessage(
    val type: ActionCardResultType,
    val actionCardIndex: Int,
    val targetPlayerId: String,
    val targetType: BoardLineTargetType,
    val lineIndex: Int,
    val inspectedValues: List<Int?>,
    val inspectedCards: List<InspectedCardDto>,
)

enum class ActionCardResultType {
    ENLIGHTENMENT,
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
