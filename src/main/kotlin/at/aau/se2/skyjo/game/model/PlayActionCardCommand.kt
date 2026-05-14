package at.aau.se2.skyjo.game.model

data class PlayActionCardCommand(
    val actionCardIndex: Int,
    val parameters: ActionCardParameters = ActionCardParameters.None,
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
