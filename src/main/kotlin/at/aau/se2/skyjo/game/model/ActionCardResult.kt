package at.aau.se2.skyjo.game.model

sealed interface ActionCardResult {
    data class Enlightenment(
        val actingPlayerId: String,
        val targetPlayerId: String,
        val targetType: BoardLineTargetType,
        val lineIndex: Int,
        val cards: List<ViewedCard>,
    ) : ActionCardResult
}

data class ViewedCard(
    val position: BoardPosition,
    val card: SkyjoCard.PlayingCard,
)
