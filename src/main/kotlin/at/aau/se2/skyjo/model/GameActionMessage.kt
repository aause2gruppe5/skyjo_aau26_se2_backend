package at.aau.se2.skyjo.model

import at.aau.se2.skyjo.game.model.DrawSource

data class GameActionMessage(
    val type: ActionType,
    val source: DrawSource? = null,
    val row: Int? = null,
    val col: Int? = null,
    val actionCardIndex: Int? = null,
)

enum class ActionType {
    DRAW,
    DRAW_VISIBLE_ACTION_CARD,
    REPLACE,
    DISCARD_AND_REVEAL,
    PLAY_ACTION_CARD,
    DISCARD_ACTION_CARD,
}
