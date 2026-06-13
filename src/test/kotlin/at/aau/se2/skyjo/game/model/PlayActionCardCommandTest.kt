package at.aau.se2.skyjo.game.model

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class PlayActionCardCommandTest {
    private val mapper = jacksonObjectMapper()

    @Test
    fun `deserializes board line target with zero based row index`() {
        val json = """
            {
              "actionCardIndex": 0,
              "parameters": {
                "targetPlayerId": "player-id",
                "targetType": "ROW",
                "lineIndex": 0
              }
            }
        """.trimIndent()

        val command = mapper.readValue<PlayActionCardCommand>(json)
        val parameters = command.parameters as ActionCardParameters.BoardLineTarget

        assertEquals(0, command.actionCardIndex)
        assertEquals("player-id", parameters.targetPlayerId)
        assertEquals(BoardLineTargetType.ROW, parameters.targetType)
        assertEquals(0, parameters.lineIndex)
    }

    @Test
    fun `deserializes board line target with column target type`() {
        val json = """
            {
              "actionCardIndex": 1,
              "parameters": {
                "targetPlayerId": "p1",
                "targetType": "COLUMN",
                "lineIndex": 2
              }
            }
        """.trimIndent()

        val command = mapper.readValue<PlayActionCardCommand>(json)
        val parameters = command.parameters as ActionCardParameters.BoardLineTarget

        assertEquals(1, command.actionCardIndex)
        assertEquals(BoardLineTargetType.COLUMN, parameters.targetType)
        assertEquals(2, parameters.lineIndex)
    }

    @Test
    fun `deserializes swap own parameters`() {
        val json = """
            {
              "actionCardIndex": 2,
              "parameters": {
                "pos1": { "row": 0, "column": 0 },
                "pos2": { "row": 0, "column": 1 }
              }
            }
        """.trimIndent()

        val command = mapper.readValue<PlayActionCardCommand>(json)
        val parameters = command.parameters as ActionCardParameters.SwapOwnParameters

        assertEquals(2, command.actionCardIndex)
        assertEquals(BoardPosition(0, 0), parameters.pos1)
        assertEquals(BoardPosition(0, 1), parameters.pos2)
    }

    @Test
    fun `deserializes draw three cards choice parameters`() {
        val json = """
            {
              "actionCardIndex": 0,
              "parameters": {
                "targetPlayerId": "p1",
                "targetRow": 1,
                "targetColumn": 2,
                "chosenDrawnCardIndex": 1,
                "discardOrder": ["SWAPPED_BOARD_CARD", "DRAWN_CARD_0", "DRAWN_CARD_2"]
              }
            }
        """.trimIndent()

        val command = mapper.readValue<PlayActionCardCommand>(json)
        val parameters = command.parameters as ActionCardParameters.DrawThreeCardsChoice

        assertEquals(0, command.actionCardIndex)
        assertEquals("p1", parameters.targetPlayerId)
        assertEquals(DrawThreeCardsChoiceMode.KEEP_ONE_AND_SWAP, parameters.mode)
        assertEquals(1, parameters.targetRow)
        assertEquals(2, parameters.targetColumn)
        assertEquals(1, parameters.chosenDrawnCardIndex)
        assertEquals(
            listOf(
                DrawThreeCardsDiscardReference.SWAPPED_BOARD_CARD,
                DrawThreeCardsDiscardReference.DRAWN_CARD_0,
                DrawThreeCardsDiscardReference.DRAWN_CARD_2,
            ),
            parameters.discardOrder,
        )
    }

    @Test
    fun `deserializes draw three cards discard all choice parameters`() {
        val json = """
            {
              "actionCardIndex": 0,
              "parameters": {
                "mode": "DISCARD_ALL_AND_REVEAL",
                "targetPlayerId": "p1",
                "revealRow": 2,
                "revealColumn": 3,
                "discardOrder": ["DRAWN_CARD_2", "DRAWN_CARD_0", "DRAWN_CARD_1"]
              }
            }
        """.trimIndent()

        val command = mapper.readValue<PlayActionCardCommand>(json)
        val parameters = command.parameters as ActionCardParameters.DrawThreeCardsChoice

        assertEquals(0, command.actionCardIndex)
        assertEquals(DrawThreeCardsChoiceMode.DISCARD_ALL_AND_REVEAL, parameters.mode)
        assertEquals("p1", parameters.targetPlayerId)
        assertEquals(2, parameters.revealRow)
        assertEquals(3, parameters.revealColumn)
        assertEquals(
            listOf(
                DrawThreeCardsDiscardReference.DRAWN_CARD_2,
                DrawThreeCardsDiscardReference.DRAWN_CARD_0,
                DrawThreeCardsDiscardReference.DRAWN_CARD_1,
            ),
            parameters.discardOrder,
        )
    }
}
