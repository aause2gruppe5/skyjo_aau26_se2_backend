package at.aau.se2.skyjo.game.model

import com.fasterxml.jackson.annotation.JsonSubTypes
import com.fasterxml.jackson.annotation.JsonTypeInfo

data class PlayActionCardCommand(
    val actionCardIndex: Int,
    val parameters: ActionCardParameters = ActionCardParameters.None,
)

@JsonTypeInfo(use = JsonTypeInfo.Id.DEDUCTION, defaultImpl = ActionCardParameters.None::class)
@JsonSubTypes(
    JsonSubTypes.Type(ActionCardParameters.None::class),
    JsonSubTypes.Type(ActionCardParameters.BoardLineTarget::class),
)
sealed interface ActionCardParameters {
    data object None : ActionCardParameters

    data class BoardLineTarget(
        val targetPlayerId: String,
        val targetType: BoardLineTargetType,
        val lineIndex: Int,
    ) : ActionCardParameters
}

enum class BoardLineTargetType {
    ROW,
    COLUMN,
}
