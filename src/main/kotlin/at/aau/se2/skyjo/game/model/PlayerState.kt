package at.aau.se2.skyjo.game.model

data class PlayerState(
    val id: String,
    val board: PlayerBoard,
    val actionCards: List<SkyjoCard.ActionCard> = emptyList(),
) {
    fun rawScore(): Int = board.rawScore() + actionCards.sumOf { it.scoreValue() }
}
