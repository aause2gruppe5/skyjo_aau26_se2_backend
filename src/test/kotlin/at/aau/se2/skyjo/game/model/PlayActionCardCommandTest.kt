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
}
