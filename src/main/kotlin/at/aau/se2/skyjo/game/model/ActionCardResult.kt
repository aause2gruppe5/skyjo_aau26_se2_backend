package at.aau.se2.skyjo.game.model

sealed interface ActionCardResult {
    val actingPlayerId: String

    data class Enlightenment(
        override val actingPlayerId: String,
        val targetPlayerId: String,
        val targetType: BoardLineTargetType,
        val lineIndex: Int,
        val cards: List<ViewedCard>,
    ) : ActionCardResult
}

data class ViewedCard(
    val position: BoardPosition,
    val card: SkyjoCard.PlayingCard?,
)
