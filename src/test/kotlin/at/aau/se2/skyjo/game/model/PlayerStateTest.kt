package at.aau.se2.skyjo.game.model

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class PlayerStateTest {
    @Test
    fun rawScoreIncludesActionCardScores(){
        val board = PlayerBoard(
            BoardLayout.POSITIONS.associateWith { position ->
                val value = if (position == BoardPosition(0, 0)) 5 else 0
                BoardSlot.Occupied(SkyjoCard.NumberCard(position.row * BoardLayout.COLUMNS + position.column, value), faceUp = true)
            },
        )
        val player = PlayerState(
            id = "p1",
            board = board,
            actionCards = listOf(
                SkyjoCard.ActionCard.Placeholder(151),
                SkyjoCard.ActionCard.Placeholder(152),
            ),
        )

        assertEquals(5 + 2 * ACTION_CARD_SCORE, player.rawScore())
    }
}
