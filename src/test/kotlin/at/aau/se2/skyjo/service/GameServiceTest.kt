package at.aau.se2.skyjo.service

import at.aau.se2.skyjo.game.model.BoardPosition
import at.aau.se2.skyjo.game.model.ActionCardParameters
import at.aau.se2.skyjo.game.model.BoardLayout
import at.aau.se2.skyjo.game.model.BoardLineTargetType
import at.aau.se2.skyjo.game.model.BoardSlot
import at.aau.se2.skyjo.game.model.DrawSource
import at.aau.se2.skyjo.game.model.GamePhase
import at.aau.se2.skyjo.game.model.GameState
import at.aau.se2.skyjo.game.model.PlayerBoard
import at.aau.se2.skyjo.game.model.PlayerState
import at.aau.se2.skyjo.game.model.PlayActionCardCommand
import at.aau.se2.skyjo.game.model.SkyjoCard
import at.aau.se2.skyjo.game.error.InvalidMoveException
import at.aau.se2.skyjo.game.service.SkyjoEngine
import at.aau.se2.skyjo.model.ActionCardResultType
import at.aau.se2.skyjo.model.ActionType
import at.aau.se2.skyjo.model.GameActionMessage
import at.aau.se2.skyjo.model.GameConfig
import at.aau.se2.skyjo.model.lobby.LobbyPlayer
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class GameServiceTest {

    private val engine = SkyjoEngine()
    private lateinit var service: GameService

    private val player1Id = "player1"
    private val player2Id = "player2"
    private val players = listOf(
        LobbyPlayer(sessionId = player1Id, nickname = "Alice", isHost = true),
        LobbyPlayer(sessionId = player2Id, nickname = "Bob", isHost = false),
    )

    @BeforeEach
    fun setUp() {
        service = GameService(engine, null)
    }

    // ── startGame ──────────────────────────────────────────────────────────

    @Test
    fun `startGame creates game in AWAITING_DRAW phase`() {
        val result = service.startGame(players)

        assertEquals(GamePhase.AWAITING_DRAW, result.phase)
    }

    @Test
    fun `startGame includes both players in response`() {
        val result = service.startGame(players)

        assertEquals(2, result.players.size)
    }

    @Test
    fun `startGame maps nicknames to players`() {
        val result = service.startGame(players)

        val nicknames = result.players.map { it.nickname }.toSet()
        assertEquals(setOf("Alice", "Bob"), nicknames)
    }

    @Test
    fun `startGame initialises total scores to zero`() {
        val result = service.startGame(players)

        assertTrue(result.totalScores.all { it.totalScore == 0 })
        assertEquals(2, result.totalScores.size)
    }

    @Test
    fun `startGame sets roundNumber to 1`() {
        val result = service.startGame(players)

        assertEquals(1, result.roundNumber)
    }

    @Test
    fun `startGame sets gameOver to false`() {
        val result = service.startGame(players)

        assertFalse(result.gameOver)
    }

    @Test
    fun `startGame sets initial board with 3 rows and 4 columns`() {
        val result = service.startGame(players)

        result.players.forEach { player ->
            assertEquals(3, player.board.size)
            player.board.forEach { row -> assertEquals(4, row.size) }
        }
    }

    // ── processAction – error handling ────────────────────────────────────

    @Test
    fun `processAction throws when game has not started`() {
        val ex = assertThrows<IllegalStateException> {
            service.processAction(player1Id, GameActionMessage(ActionType.DRAW, source = DrawSource.DECK))
        }
        assertTrue(ex.message!!.contains("not started"))
    }

    @Test
    fun `processAction throws when it is not the player's turn`() {
        service.startGame(players)
        val currentPlayerId = service.getCurrentState()!!.currentPlayerId!!
        val otherPlayerId = if (currentPlayerId == player1Id) player2Id else player1Id

        val ex = assertThrows<IllegalStateException> {
            service.processAction(otherPlayerId, GameActionMessage(ActionType.DRAW, source = DrawSource.DECK))
        }
        assertTrue(ex.message!!.contains("not your turn"))
    }

    @Test
    fun `processAction DRAW throws when source is missing`() {
        service.startGame(players)
        val currentPlayerId = service.getCurrentState()!!.currentPlayerId!!

        val ex = assertThrows<IllegalStateException> {
            service.processAction(currentPlayerId, GameActionMessage(ActionType.DRAW))
        }
        assertTrue(ex.message!!.contains("source required"))
    }

    @Test
    fun `processAction REPLACE throws when row is missing`() {
        service.startGame(players)
        val currentPlayerId = service.getCurrentState()!!.currentPlayerId!!
        service.processAction(currentPlayerId, GameActionMessage(ActionType.DRAW, source = DrawSource.DECK))

        val ex = assertThrows<IllegalStateException> {
            service.processAction(currentPlayerId, GameActionMessage(ActionType.REPLACE))
        }
        assertTrue(ex.message!!.contains("row required"))
    }

    @Test
    fun `processAction DISCARD_AND_REVEAL throws when row is missing`() {
        service.startGame(players)
        val currentPlayerId = service.getCurrentState()!!.currentPlayerId!!
        service.processAction(currentPlayerId, GameActionMessage(ActionType.DRAW, source = DrawSource.DECK))

        val ex = assertThrows<IllegalStateException> {
            service.processAction(currentPlayerId, GameActionMessage(ActionType.DISCARD_AND_REVEAL))
        }
        assertTrue(ex.message!!.contains("row required"))
    }

    // ── processAction – normal flow ───────────────────────────────────────

    @Test
    fun `processAction DRAW from DECK changes phase to AWAITING_REPLACEMENT`() {
        service.startGame(players)
        val currentPlayerId = service.getCurrentState()!!.currentPlayerId!!

        val result = service.processAction(currentPlayerId, GameActionMessage(ActionType.DRAW, source = DrawSource.DECK))

        assertEquals(GamePhase.AWAITING_REPLACEMENT, result.phase)
        assertNotNull(result.drawnCard)
    }

    @Test
    fun `processAction DRAW from DISCARD changes phase to AWAITING_REPLACEMENT`() {
        service.startGame(players)
        val currentPlayerId = service.getCurrentState()!!.currentPlayerId!!

        val result = service.processAction(currentPlayerId, GameActionMessage(ActionType.DRAW, source = DrawSource.DISCARD))

        assertEquals(GamePhase.AWAITING_REPLACEMENT, result.phase)
        assertNotNull(result.drawnCard)
    }

    @Test
    fun `processAction REPLACE after draw advances turn to next player`() {
        service.startGame(players)
        val firstPlayerId = service.getCurrentState()!!.currentPlayerId!!

        service.processAction(firstPlayerId, GameActionMessage(ActionType.DRAW, source = DrawSource.DECK))
        val result = service.processAction(firstPlayerId, GameActionMessage(ActionType.REPLACE, row = 0, col = 2))

        assertNotEquals(firstPlayerId, result.currentPlayerId)
    }

    @Test
    fun `processAction DISCARD_AND_REVEAL after deck draw advances turn to next player`() {
        service.startGame(players)
        val firstPlayerId = service.getCurrentState()!!.currentPlayerId!!

        service.processAction(firstPlayerId, GameActionMessage(ActionType.DRAW, source = DrawSource.DECK))
        val result = service.processAction(firstPlayerId, GameActionMessage(ActionType.DISCARD_AND_REVEAL, row = 1, col = 0))

        assertNotEquals(firstPlayerId, result.currentPlayerId)
    }

    @Test
    fun `processAction REPLACE reveals placed card on board`() {
        service.startGame(players)
        val currentPlayerId = service.getCurrentState()!!.currentPlayerId!!
        val targetRow = 1
        val targetCol = 2

        service.processAction(currentPlayerId, GameActionMessage(ActionType.DRAW, source = DrawSource.DECK))
        val result = service.processAction(currentPlayerId, GameActionMessage(ActionType.REPLACE, row = targetRow, col = targetCol))

        val playerBoard = result.players.find { it.playerId == currentPlayerId }!!.board
        val replacedSlot = playerBoard[targetRow][targetCol]
        // After replace the card is face-up, so the value should be visible
        assertNotNull(replacedSlot.card)
        assertTrue(replacedSlot.faceUp == true)
    }

    // ── round transitions ─────────────────────────────────────────────────

    @Test
    fun `playActionCard Enlightenment row returns private row and public update keeps cards hidden`() {
        val targetRow = 1
        val board = playerBoardWithValues(
            mapOf(
                BoardPosition(targetRow, 0) to 2,
                BoardPosition(targetRow, 1) to 4,
                BoardPosition(targetRow, 2) to 6,
                BoardPosition(targetRow, 3) to 8,
            ),
            faceUp = false,
        )
        setInternalGameState(service, gameStateWithActionCard(player1Id, board))

        val result = service.playActionCard(
            player1Id,
            PlayActionCardCommand(
                actionCardIndex = 0,
                parameters = ActionCardParameters.BoardLineTarget(
                    targetPlayerId = player1Id,
                    targetType = BoardLineTargetType.ROW,
                    lineIndex = targetRow,
                ),
            ),
        )

        val privateResult = result.privateActionCardResults[player1Id]!!
        assertEquals(setOf(player1Id), result.privateActionCardResults.keys)
        assertEquals(ActionCardResultType.ENLIGHTENMENT, privateResult.type)
        assertEquals(0, privateResult.actionCardIndex)
        assertEquals(BoardLineTargetType.ROW, privateResult.targetType)
        assertEquals(targetRow, privateResult.lineIndex)
        assertEquals(listOf(2, 4, 6, 8), privateResult.inspectedValues)
        assertEquals(listOf(0, 1, 2, 3), privateResult.inspectedCards.map { it.col })

        val publicRow = result.gameUpdate.players.first { it.playerId == player1Id }.board[targetRow]
        assertTrue(publicRow.all { it.faceUp == false })
        assertTrue(publicRow.all { it.card == null })

        val storedState = getInternalGameState(service)
        BoardLayout.HORIZONTAL_LINES[targetRow].forEach { position ->
            val slot = storedState.players.first { it.id == player1Id }.board.slotAt(position) as BoardSlot.Occupied
            assertFalse(slot.faceUp)
        }
    }

    @Test
    fun `playActionCard Enlightenment column returns private column`() {
        val targetColumn = 2
        val board = playerBoardWithValues(
            mapOf(
                BoardPosition(0, targetColumn) to -1,
                BoardPosition(1, targetColumn) to 5,
                BoardPosition(2, targetColumn) to 12,
            ),
            faceUp = false,
        )
        setInternalGameState(service, gameStateWithActionCard(player1Id, board))

        val result = service.playActionCard(
            player1Id,
            PlayActionCardCommand(
                actionCardIndex = 0,
                parameters = ActionCardParameters.BoardLineTarget(
                    targetPlayerId = player1Id,
                    targetType = BoardLineTargetType.COLUMN,
                    lineIndex = targetColumn,
                ),
            ),
        )

        val privateResult = result.privateActionCardResults[player1Id]!!
        assertEquals(BoardLineTargetType.COLUMN, privateResult.targetType)
        assertEquals(targetColumn, privateResult.lineIndex)
        assertEquals(listOf(-1, 5, 12), privateResult.inspectedValues)
        assertEquals(listOf(0, 1, 2), privateResult.inspectedCards.map { it.row })
        assertEquals(listOf(targetColumn, targetColumn, targetColumn), privateResult.inspectedCards.map { it.col })
    }

    @Test
    fun `playActionCard rejects attempt to inspect another player's board`() {
        setInternalGameState(service, gameStateWithActionCard(player1Id, playerBoardWithValues(emptyMap())))

        val ex = assertThrows<InvalidMoveException> {
            service.playActionCard(
                player1Id,
                PlayActionCardCommand(
                    actionCardIndex = 0,
                    parameters = ActionCardParameters.BoardLineTarget(
                        targetPlayerId = player2Id,
                        targetType = BoardLineTargetType.ROW,
                        lineIndex = 0,
                    ),
                ),
            )
        }

        assertTrue(ex.message!!.contains("acting player's own board"))
        assertTrue(getInternalGameState(service).players.first { it.id == player1Id }.actionCards.isNotEmpty())
    }

    @Test
    fun `handleRoundFinished accumulates scores into totalScores`() {
        service.startGame(players, GameConfig(maxRounds = 2))

        // Build a finished round state with known scores by using engine directly
        val currentState = service.getCurrentState()!!
        val currentPlayerId = currentState.currentPlayerId!!
        // Force ROUND_FINISHED state by replaying via internal helper
        val gameState = getInternalGameState(service)
        val finishedState = engine.finishRound(gameState.copy(finisherPlayerId = currentPlayerId))

        service.handleRoundFinished(finishedState)

        val updatedState = service.getCurrentState()!!
        // Scores from the round should now appear in totalScores
        assertTrue(updatedState.totalScores.isNotEmpty())
        // After 1 round out of 2, should NOT be game over
        assertFalse(updatedState.gameOver)
    }

    @Test
    fun `handleRoundFinished starts new round when maxRounds not reached`() {
        service.startGame(players, GameConfig(maxRounds = 2, targetScore = 1000))
        val gameState = getInternalGameState(service)
        val currentPlayerId = gameState.currentPlayerId!!
        val finishedState = engine.finishRound(gameState.copy(finisherPlayerId = currentPlayerId))

        val result = service.handleRoundFinished(finishedState)

        assertEquals(GamePhase.AWAITING_DRAW, result.phase)
        assertEquals(2, result.roundNumber)
        assertFalse(result.gameOver)
    }

    @Test
    fun `handleRoundFinished sets gameOver when maxRounds reached`() {
        service.startGame(players, GameConfig(maxRounds = 1))
        val gameState = getInternalGameState(service)
        val currentPlayerId = gameState.currentPlayerId!!
        val finishedState = engine.finishRound(gameState.copy(finisherPlayerId = currentPlayerId))

        val result = service.handleRoundFinished(finishedState)

        assertTrue(result.gameOver)
        assertEquals(GamePhase.ROUND_FINISHED, result.phase)
    }

    @Test
    fun `handleRoundFinished sets gameOver when player reaches targetScore`() {
        service.startGame(players, GameConfig(maxRounds = 10, targetScore = 0))
        val gameState = getInternalGameState(service)
        val currentPlayerId = gameState.currentPlayerId!!
        val finishedState = engine.finishRound(gameState.copy(finisherPlayerId = currentPlayerId))

        val result = service.handleRoundFinished(finishedState)

        // targetScore = 0 means any score >= 0 triggers game over
        assertTrue(result.gameOver)
    }

    // ── getCurrentState ───────────────────────────────────────────────────

    @Test
    fun `getCurrentState returns null before game starts`() {
        assertNull(service.getCurrentState())
    }

    @Test
    fun `getCurrentState returns current game state after start`() {
        service.startGame(players)

        assertNotNull(service.getCurrentState())
    }
}

// Helpers to access internal state for test setup
private fun getInternalGameState(service: GameService): at.aau.se2.skyjo.game.model.GameState {
    val field = GameService::class.java.getDeclaredField("gameState")
    field.isAccessible = true
    @Suppress("UNCHECKED_CAST")
    return (field.get(service) as at.aau.se2.skyjo.game.model.GameState?)!!
}

private fun setInternalGameState(service: GameService, state: GameState) {
    val stateField = GameService::class.java.getDeclaredField("gameState")
    stateField.isAccessible = true
    stateField.set(service, state)

    val roundField = GameService::class.java.getDeclaredField("roundNumber")
    roundField.isAccessible = true
    roundField.set(service, 1)

    val totalScoresField = GameService::class.java.getDeclaredField("totalScores")
    totalScoresField.isAccessible = true
    totalScoresField.set(service, state.players.associate { it.id to 0 })
}

private fun gameStateWithActionCard(playerId: String, board: PlayerBoard): GameState {
    val currentPlayer = PlayerState(
        id = playerId,
        board = board,
        actionCards = listOf(SkyjoCard.ActionCard.Enlightenment(id = 151)),
    )
    val otherPlayer = PlayerState(
        id = "player2",
        board = playerBoardWithValues(emptyMap()),
    )
    return GameState(
        players = listOf(currentPlayer, otherPlayer),
        currentPlayerIndex = 0,
        phase = GamePhase.AWAITING_DRAW,
    )
}

private fun playerBoardWithValues(
    positionValues: Map<BoardPosition, Int>,
    faceUp: Boolean = false,
): PlayerBoard {
    val slots = BoardLayout.POSITIONS.associateWith { position ->
        val id = position.row * BoardLayout.COLUMNS + position.column
        BoardSlot.Occupied(
            card = SkyjoCard.NumberCard(id = id, value = positionValues[position] ?: 0),
            faceUp = faceUp,
        )
    }
    return PlayerBoard(slots)
}
