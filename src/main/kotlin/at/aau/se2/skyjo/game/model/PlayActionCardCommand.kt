package at.aau.se2.skyjo.game.model

data class PlayActionCardCommand(
    val actionCardIndex: Int,
    val parameters: ActionCardParameters = ActionCardParameters.None,
)

sealed interface ActionCardParameters {
    data object None : ActionCardParameters
}
