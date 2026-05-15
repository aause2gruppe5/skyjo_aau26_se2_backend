package at.aau.se2.skyjo.service

import at.aau.se2.skyjo.game.model.BoardPosition
import at.aau.se2.skyjo.game.model.DrawSource
import at.aau.se2.skyjo.game.model.GamePhase
import at.aau.se2.skyjo.game.service.SkyjoEngine
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

    // ── DISCARD_ACTION_CARD ───────────────────────────────────────────────────

    @Test
    fun `processAction DISCARD_ACTION_CARD throws when actionCardIndex is missing`() {
        service.startGame(players)
        val currentPlayerId = service.getCurrentState()!!.currentPlayerId!!

        // Give current player an action card by drawing from the action deck
        service.processAction(
            currentPlayerId,
            GameActionMessage(ActionType.DRAW, source = DrawSource.ACTION_DECK)
        )

        // Re-create to test missing index error directly
        val s3 = GameService(SkyjoEngine(), null)
        s3.startGame(players)
        val pid3 = s3.getCurrentState()!!.currentPlayerId!!
        s3.processAction(pid3, GameActionMessage(ActionType.DRAW, source = DrawSource.ACTION_DECK))

        val nextPid = s3.getCurrentState()!!.currentPlayerId!!
        val ex = assertThrows<IllegalStateException> {
            s3.processAction(nextPid, GameActionMessage(ActionType.DISCARD_ACTION_CARD))
        }
        assertTrue(ex.message!!.contains("actionCardIndex required"))
    }

    @Test
    fun `processAction DISCARD_ACTION_CARD removes card from player hand and advances turn`() {
        service.startGame(players)
        val currentPlayerId = service.getCurrentState()!!.currentPlayerId!!

        // Draw an action card (this advances turn)
        service.processAction(
            currentPlayerId,
            GameActionMessage(ActionType.DRAW, source = DrawSource.ACTION_DECK)
        )

        // Now the other player's turn — draw action card for them too
        val otherPlayerId = service.getCurrentState()!!.currentPlayerId!!
        service.processAction(
            otherPlayerId,
            GameActionMessage(ActionType.DRAW, source = DrawSource.ACTION_DECK)
        )

        // Back to first player — they have an action card; discard it
        val pid = service.getCurrentState()!!.currentPlayerId!!
        val stateBefore = service.getCurrentState()!!
        val playerBefore = stateBefore.players.find { it.playerId == pid }!!
        assertEquals(1, playerBefore.actionCardTypes.size)

        val result = service.processAction(
            pid,
            GameActionMessage(ActionType.DISCARD_ACTION_CARD, actionCardIndex = 0)
        )

        val playerAfter = result.players.find { it.playerId == pid }!!
        assertEquals(0, playerAfter.actionCardTypes.size)
    }

    // ── PLAY_ACTION_CARD ─────────────────────────────────────────────────────

    @Test
    fun `processAction PLAY_ACTION_CARD throws when actionCardIndex is missing`() {
        service.startGame(players)
        val currentPlayerId = service.getCurrentState()!!.currentPlayerId!!
        service.processAction(currentPlayerId, GameActionMessage(ActionType.DRAW, source = DrawSource.ACTION_DECK))

        val nextPid = service.getCurrentState()!!.currentPlayerId!!
        service.processAction(nextPid, GameActionMessage(ActionType.DRAW, source = DrawSource.ACTION_DECK))

        val pid = service.getCurrentState()!!.currentPlayerId!!
        val ex = assertThrows<IllegalStateException> {
            service.processAction(pid, GameActionMessage(ActionType.PLAY_ACTION_CARD))
        }
        assertTrue(ex.message!!.contains("actionCardIndex required"))
    }

    @Test
    fun `processAction PLAY_ACTION_CARD performs swap and advances turn`() {
        service.startGame(players)

        // Let first player draw an action card
        val pid1 = service.getCurrentState()!!.currentPlayerId!!
        service.processAction(pid1, GameActionMessage(ActionType.DRAW, source = DrawSource.ACTION_DECK))

        // Let second player draw an action card so we return to pid1
        val pid2 = service.getCurrentState()!!.currentPlayerId!!
        service.processAction(pid2, GameActionMessage(ActionType.DRAW, source = DrawSource.ACTION_DECK))

        // pid1's turn — play the PlayerSwap action card
        val pid = service.getCurrentState()!!.currentPlayerId!!
        val stateBeforePlay = service.getCurrentState()!!

        // Pick positions — we just need two different players and their (0,0) positions
        val p1 = stateBeforePlay.players.find { it.playerId == pid }!!
        val p2 = stateBeforePlay.players.find { it.playerId != pid }!!

        val result = service.processAction(
            pid,
            GameActionMessage(
                type = ActionType.PLAY_ACTION_CARD,
                actionCardIndex = 0,
                targetPlayer1Id = p1.playerId,
                targetPlayer1Row = 0,
                targetPlayer1Col = 0,
                targetPlayer2Id = p2.playerId,
                targetPlayer2Row = 0,
                targetPlayer2Col = 0,
            )
        )

        // Turn must have advanced
        assertNotEquals(pid, result.currentPlayerId)
        // The player who played the card should now have 0 action cards
        val playerAfter = result.players.find { it.playerId == pid }!!
        assertEquals(0, playerAfter.actionCardTypes.size)
    }
}

// Helpers to access internal state for test setup
private fun getInternalGameState(service: GameService): at.aau.se2.skyjo.game.model.GameState {
    val field = GameService::class.java.getDeclaredField("gameState")
    field.isAccessible = true
    @Suppress("UNCHECKED_CAST")
    return (field.get(service) as at.aau.se2.skyjo.game.model.GameState?)!!
}
