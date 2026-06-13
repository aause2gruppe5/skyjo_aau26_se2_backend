package at.aau.se2.skyjo.game.model

import com.fasterxml.jackson.annotation.JsonSubTypes
import com.fasterxml.jackson.annotation.JsonTypeInfo

data class PlayActionCardCommand(
    val actionCardIndex: Int,
    val parameters: ActionCardParameters = ActionCardParameters.None,
)

@JsonTypeInfo(use = JsonTypeInfo.Id.DEDUCTION, defaultImpl = ActionCardParameters.None::class)
@JsonSubTypes(
    JsonSubTypes.Type(ActionCardParameters.None::class),
    JsonSubTypes.Type(ActionCardParameters.BoardLineTarget::class),
    JsonSubTypes.Type(ActionCardParameters.SwapOwnParameters::class),
    JsonSubTypes.Type(ActionCardParameters.PlayerSwap::class),
    JsonSubTypes.Type(ActionCardParameters.DrawThreeCardsChoice::class),
)
sealed interface ActionCardParameters {
    data object None : ActionCardParameters

    data class BoardLineTarget(
        val targetPlayerId: String,
        val targetType: BoardLineTargetType,
        val lineIndex: Int,
    ) : ActionCardParameters

    data class SwapOwnParameters(
        val pos1: BoardPosition,
        val pos2: BoardPosition,
    ) : ActionCardParameters

    data class PlayerSwap(
        val player1Id: String,
        val player1Position: BoardPosition,
        val player2Id: String,
        val player2Position: BoardPosition,
    ) : ActionCardParameters

    data class DrawThreeCardsChoice(
        val mode: DrawThreeCardsChoiceMode = DrawThreeCardsChoiceMode.KEEP_ONE_AND_SWAP,
        val chosenDrawnCardIndex: Int? = null,
        val targetRow: Int? = null,
        val targetColumn: Int? = null,
        val revealRow: Int? = null,
        val revealColumn: Int? = null,
        val discardOrder: List<DrawThreeCardsDiscardReference>,
        val targetPlayerId: String? = null,
    ) : ActionCardParameters
}

enum class BoardLineTargetType {
    ROW,
    COLUMN,
}

enum class DrawThreeCardsDiscardReference {
    DRAWN_CARD_0,
    DRAWN_CARD_1,
    DRAWN_CARD_2,
    SWAPPED_BOARD_CARD,
}

enum class DrawThreeCardsChoiceMode {
    KEEP_ONE_AND_SWAP,
    DISCARD_ALL_AND_REVEAL,
}
