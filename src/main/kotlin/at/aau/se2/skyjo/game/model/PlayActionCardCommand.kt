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
    JsonSubTypes.Type(ActionCardParameters.SwapOwnParameters::class),
    JsonSubTypes.Type(ActionCardParameters.PlayerSwap::class),
)
sealed interface ActionCardParameters {
    data object None : ActionCardParameters

    data class BoardLineTarget(
        val targetPlayerId: String,
        val targetType: BoardLineTargetType,
        val lineIndex: Int,
    ) : ActionCardParameters

    data class SwapOwnParameters(
        val pos1: BoardPosition,
        val faceUp1: Boolean,
        val pos2: BoardPosition,
        val faceUp2: Boolean,
    ) : ActionCardParameters

    data class PlayerSwap(
        val player1Id: String,
        val player1Position: BoardPosition,
        val player2Id: String,
        val player2Position: BoardPosition,
    ) : ActionCardParameters
}

enum class BoardLineTargetType {
    ROW,
    COLUMN,
}
