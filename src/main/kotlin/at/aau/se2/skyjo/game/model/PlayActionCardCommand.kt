package at.aau.se2.skyjo.game.model

data class PlayActionCardCommand(
    val actionCardIndex: Int,
    val parameters: ActionCardParameters = ActionCardParameters.None,
)

sealed interface ActionCardParameters {
    data object None : ActionCardParameters

    data class SwapOwnParameters(
        val pos1: BoardPosition,
        val faceUp1: Boolean,
        val pos2: BoardPosition,
        val faceUp2: Boolean,
    ) : ActionCardParameters
}
