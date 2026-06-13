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

    data class DrawThreeCards(
        override val actingPlayerId: String,
        val cards: List<SkyjoCard.PlayingCard>,
    ) : ActionCardResult
}

data class ViewedCard(
    val position: BoardPosition,
    val card: SkyjoCard.PlayingCard?,
)

sealed interface PendingActionCard {
    val actingPlayerId: String
    val actionCardIndex: Int
    val actionCardId: Int

    data class DrawThreeCards(
        override val actingPlayerId: String,
        override val actionCardIndex: Int,
        override val actionCardId: Int,
        val cards: List<SkyjoCard.PlayingCard>,
    ) : PendingActionCard
}
