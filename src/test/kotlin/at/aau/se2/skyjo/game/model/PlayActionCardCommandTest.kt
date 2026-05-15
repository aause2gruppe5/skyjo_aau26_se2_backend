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
                "targetPlayerId": "p1",
                "targetType": "ROW",
                "lineIndex": 0
              }
            }
        """.trimIndent()

        val command = mapper.readValue<PlayActionCardCommand>(json)
        val parameters = command.parameters as ActionCardParameters.BoardLineTarget

        assertEquals(0, command.actionCardIndex)
        assertEquals("p1", parameters.targetPlayerId)
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
}
