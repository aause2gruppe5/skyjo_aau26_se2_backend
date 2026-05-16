package at.aau.se2.skyjo.game.model

data class PlayActionCardCommand(
    val actionCardIndex: Int,
    val parameters: ActionCardParameters = ActionCardParameters.None,
)

sealed interface ActionCardParameters {
    data object None : ActionCardParameters

    data class PlayerSwap(
        val player1Id: String,
        val player1Position: BoardPosition,
        val player2Id: String,
        val player2Position: BoardPosition,
    ) : ActionCardParameters
}
