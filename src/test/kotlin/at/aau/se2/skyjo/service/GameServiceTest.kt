package at.aau.se2.skyjo.service

import at.aau.se2.skyjo.game.model.ActionCardParameters
import at.aau.se2.skyjo.game.model.BoardLayout
import at.aau.se2.skyjo.game.model.BoardLineTargetType
import at.aau.se2.skyjo.game.model.BoardPosition
import at.aau.se2.skyjo.game.model.BoardSlot
import at.aau.se2.skyjo.game.model.DrawSource
import at.aau.se2.skyjo.game.model.GamePhase
import at.aau.se2.skyjo.game.model.GameState
import at.aau.se2.skyjo.game.model.PlayerBoard
import at.aau.se2.skyjo.game.model.PlayerState
import at.aau.se2.skyjo.game.model.PlayActionCardCommand
import at.aau.se2.skyjo.game.model.SkyjoCard
import at.aau.se2.skyjo.game.service.SkyjoEngine
import at.aau.se2.skyjo.model.ActionCardKind
import at.aau.se2.skyjo.model.ActionCardResultType
import at.aau.se2.skyjo.model.ActionType
import at.aau.se2.skyjo.model.GameActionMessage
import at.aau.se2.skyjo.model.GameConfig
import at.aau.se2.skyjo.model.lobby.LobbyPlayer
import at.aau.se2.skyjo.persistence.AuthRepository
import at.aau.se2.skyjo.persistence.GameRepository
import at.aau.se2.skyjo.persistence.StatsRepository
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import at.aau.se2.skyjo.game.error.InvalidMoveException
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.datasource.SingleConnectionDataSource

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

    private fun otherPlayerId(playerId: String): String =
        if (playerId == player1Id) player2Id else player1Id

    private fun advanceTurnByDrawingActionCard() {
        val currentPlayerId = service.getCurrentState()!!.currentPlayerId!!
        service.processAction(currentPlayerId, GameActionMessage(ActionType.DRAW, source = DrawSource.ACTION_DECK))
    }

    private fun finishRoundForNextRound() {
        val state = getInternalGameState(service).copy(phase = GamePhase.ROUND_FINISHED)
        setInternalGameState(service, state)
    }

    private fun totalScoreOf(playerId: String): Int =
        service.getCurrentState()!!.totalScores.first { it.playerId == playerId }.totalScore

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

    @Test
    fun `startGame exposes visible action cards and action draw pile count`() {
        val result = service.startGame(players)

        assertEquals(4, result.visibleActionCards.size)
        assertEquals(13, result.actionDrawPileCount)
        assertTrue(result.visibleActionCards.all { it.value == 10 })
        assertTrue(
            result.visibleActionCards.all {
                it.kind in setOf(
                    ActionCardKind.ENLIGHTENMENT,
                    ActionCardKind.DEFENSE,
                    ActionCardKind.SWAP_OWN_CARDS,
                    ActionCardKind.PLAYER_SWAP,
                    ActionCardKind.DOUBLE_TURN,
                    ActionCardKind.DRAW_THREE_CARDS,
                )
            },
        )
        assertTrue(result.players.all { it.actionCards.isEmpty() })
    }

    @Test
    fun `cheatPeekDrawPile reveals top draw card privately without consuming turn`() {
        val game = service.startGame(players)
        val currentPlayerId = game.currentPlayerId!!

        val peek = service.cheatPeekDrawPile(currentPlayerId)
        val stateAfterPeek = service.getCurrentState(currentPlayerId)!!

        assertNotNull(peek.card.value)
        assertEquals(2, peek.remainingCheatPeeks)
        assertEquals(GamePhase.AWAITING_DRAW, stateAfterPeek.phase)
        assertEquals(currentPlayerId, stateAfterPeek.currentPlayerId)
        assertNull(stateAfterPeek.drawnCard)
    }

    @Test
    fun `cheatPeekDrawPile is limited to three uses per player`() {
        val game = service.startGame(players)
        val currentPlayerId = game.currentPlayerId!!

        assertEquals(2, service.cheatPeekDrawPile(currentPlayerId).remainingCheatPeeks)
        assertEquals(1, service.cheatPeekDrawPile(currentPlayerId).remainingCheatPeeks)
        assertEquals(0, service.cheatPeekDrawPile(currentPlayerId).remainingCheatPeeks)

        val exception = assertThrows<IllegalStateException> {
            service.cheatPeekDrawPile(currentPlayerId)
        }
        assertTrue(exception.message!!.contains("no cheat peeks left"))
    }

    @Test
    fun `cheatPeekDrawPile limit resets when next round starts`() {
        val game = service.startGame(players)
        val currentPlayerId = game.currentPlayerId!!

        repeat(3) { service.cheatPeekDrawPile(currentPlayerId) }
        assertThrows<IllegalStateException> {
            service.cheatPeekDrawPile(currentPlayerId)
        }

        finishRoundForNextRound()
        val nextRound = service.processAction(player1Id, GameActionMessage(ActionType.START_NEXT_ROUND))
        val nextCurrentPlayerId = nextRound.currentPlayerId!!

        assertEquals(2, service.cheatPeekDrawPile(nextCurrentPlayerId).remainingCheatPeeks)
    }

    @Test
    fun `cheatReportCurrentPlayer penalizes cheater when current player cheated this turn`() {
        val game = service.startGame(players)
        val cheaterId = game.currentPlayerId!!
        val reporterId = otherPlayerId(cheaterId)

        service.cheatPeekDrawPile(cheaterId)
        val result = service.cheatReportCurrentPlayer(reporterId)

        assertTrue(result.privateReportResult.successful)
        assertEquals(cheaterId, result.privateReportResult.targetPlayerId)
        assertEquals(cheaterId, result.privateReportResult.penaltyPlayerId)
        assertEquals(10, result.privateReportResult.penaltyPoints)
        assertEquals(2, result.privateReportResult.remainingCheatReports)
        assertEquals(10, result.gameUpdate.totalScores.first { it.playerId == cheaterId }.totalScore)
        assertEquals(2, result.gameUpdate.players.first { it.playerId == reporterId }.remainingCheatReports)
    }

    @Test
    fun `cheatReportCurrentPlayer penalizes reporter when no cheat happened this turn`() {
        val game = service.startGame(players)
        val currentPlayerId = game.currentPlayerId!!
        val reporterId = otherPlayerId(currentPlayerId)

        val result = service.cheatReportCurrentPlayer(reporterId)

        assertFalse(result.privateReportResult.successful)
        assertEquals(currentPlayerId, result.privateReportResult.targetPlayerId)
        assertEquals(reporterId, result.privateReportResult.penaltyPlayerId)
        assertEquals(5, result.privateReportResult.penaltyPoints)
        assertEquals(2, result.privateReportResult.remainingCheatReports)
        assertEquals(5, result.gameUpdate.totalScores.first { it.playerId == reporterId }.totalScore)
    }

    @Test
    fun `cheatReportCurrentPlayer rejects duplicate report in same turn without another penalty`() {
        val game = service.startGame(players)
        val currentPlayerId = game.currentPlayerId!!
        val reporterId = otherPlayerId(currentPlayerId)

        service.cheatReportCurrentPlayer(reporterId)
        val exception = assertThrows<IllegalStateException> {
            service.cheatReportCurrentPlayer(reporterId)
        }

        assertTrue(exception.message!!.contains("already reported"))
        assertEquals(5, totalScoreOf(reporterId))
        assertEquals(2, service.getCurrentState()!!.players.first { it.playerId == reporterId }.remainingCheatReports)
    }

    @Test
    fun `cheatReportCurrentPlayer is limited to three reports per round`() {
        val game = service.startGame(players)
        val originalCurrentPlayerId = game.currentPlayerId!!
        val reporterId = otherPlayerId(originalCurrentPlayerId)

        repeat(3) { index ->
            val result = service.cheatReportCurrentPlayer(reporterId)
            assertEquals(2 - index, result.privateReportResult.remainingCheatReports)
            advanceTurnByDrawingActionCard()
            advanceTurnByDrawingActionCard()
        }

        val exception = assertThrows<IllegalStateException> {
            service.cheatReportCurrentPlayer(reporterId)
        }
        assertTrue(exception.message!!.contains("no cheat reports left"))
    }

    @Test
    fun `cheatReportCurrentPlayer report limit resets when next round starts`() {
        val game = service.startGame(players)
        val reporterId = otherPlayerId(game.currentPlayerId!!)

        service.cheatReportCurrentPlayer(reporterId)
        assertEquals(2, service.getCurrentState()!!.players.first { it.playerId == reporterId }.remainingCheatReports)

        finishRoundForNextRound()
        val nextRound = service.processAction(player1Id, GameActionMessage(ActionType.START_NEXT_ROUND))

        assertTrue(nextRound.players.all { it.remainingCheatReports == 3 })
    }

    @Test
    fun `cheatReportCurrentPlayer rejects reporting yourself`() {
        val game = service.startGame(players)
        val currentPlayerId = game.currentPlayerId!!

        val exception = assertThrows<IllegalStateException> {
            service.cheatReportCurrentPlayer(currentPlayerId)
        }

        assertTrue(exception.message!!.contains("cannot report yourself"))
    }

    @Test
    fun `cheatPeekDrawPile throws when game has not started`() {
        val exception = assertThrows<IllegalStateException> {
            service.cheatPeekDrawPile(player1Id)
        }

        assertTrue(exception.message!!.contains("not started"))
    }

    @Test
    fun `cheatPeekDrawPile throws when it is not the player's turn`() {
        val game = service.startGame(players)
        val currentPlayerId = game.currentPlayerId!!
        val otherPlayerId = if (currentPlayerId == player1Id) player2Id else player1Id

        val exception = assertThrows<IllegalStateException> {
            service.cheatPeekDrawPile(otherPlayerId)
        }

        assertTrue(exception.message!!.contains("not your turn"))
    }

    @Test
    fun `startGame clears disconnected players from previous game`() {
        service.startGame(players)
        service.markPlayerDisconnected(player1Id)
        assertTrue(service.getCurrentState()!!.disconnectedPlayers.contains("Alice"))

        val result = service.startGame(players)

        assertTrue(result.disconnectedPlayers.isEmpty())
    }

    @Test
    fun `startGame can run separate lobby games with player routing`() {
        val first = service.startGame("lobby-1", players)
        val secondPlayers = listOf(
            LobbyPlayer(sessionId = "session-3", nickname = "Cara", isHost = true, userId = "player3"),
            LobbyPlayer(sessionId = "session-4", nickname = "Dan", isHost = false, userId = "player4"),
        )

        val second = service.startGame("lobby-2", secondPlayers)

        assertNotEquals(first.gameId, second.gameId)
        assertEquals("lobby-1", first.lobbyId)
        assertEquals("lobby-2", second.lobbyId)
        assertEquals(first.gameId, service.getCurrentState(player1Id)?.gameId)
        assertEquals(second.gameId, service.getCurrentState("player3")?.gameId)

        val firstCurrentPlayer = service.getCurrentState(player1Id)!!.currentPlayerId!!
        val firstAfterAction = service.processAction(
            firstCurrentPlayer,
            GameActionMessage(ActionType.DRAW, source = DrawSource.DECK),
        )

        assertEquals(first.gameId, firstAfterAction.gameId)
        assertEquals(second.gameId, service.getCurrentState("player3")?.gameId)
        assertEquals(GamePhase.AWAITING_DRAW, service.getCurrentState("player3")?.phase)
    }

    @Test
    fun `markPlayerDisconnected returns disconnected player's own game state`() {
        val first = service.startGame("lobby-1", players)
        val secondPlayers = listOf(
            LobbyPlayer(sessionId = "session-3", nickname = "Cara", isHost = true, userId = "player3"),
            LobbyPlayer(sessionId = "session-4", nickname = "Dan", isHost = false, userId = "player4"),
        )
        val second = service.startGame("lobby-2", secondPlayers)

        val update = service.markPlayerDisconnected(player1Id)

        assertEquals(first.gameId, update?.gameId)
        assertTrue(update?.disconnectedPlayers?.contains("Alice") == true)
        assertEquals(second.gameId, service.getCurrentState()?.gameId)
    }

    @Test
    fun `reconnectPlayer restores a non-current game by game id and authenticated user id`() {
        val first = service.startGame("lobby-1", players)
        val secondPlayers = listOf(
            LobbyPlayer(sessionId = "session-3", nickname = "Cara", isHost = true, userId = "player3"),
            LobbyPlayer(sessionId = "session-4", nickname = "Dan", isHost = false, userId = "player4"),
        )
        service.startGame("lobby-2", secondPlayers)
        service.markPlayerDisconnected(player1Id)

        val update = service.reconnectPlayer(player1Id, "Alice", first.gameId!!)

        assertEquals(first.gameId, update?.gameId)
        assertFalse(update?.disconnectedPlayers?.contains("Alice") == true)
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

    @Test
    fun `processAction PLAY_ACTION_CARD throws when index is missing`() {
        service.startGame(players)
        val currentPlayerId = service.getCurrentState()!!.currentPlayerId!!

        val exception = assertThrows<IllegalStateException> {
            service.processAction(currentPlayerId, GameActionMessage(ActionType.PLAY_ACTION_CARD))
        }

        assertTrue(exception.message!!.contains("actionCardIndex required"))
    }

    @Test
    fun `processAction DRAW_VISIBLE_ACTION_CARD throws when index is missing`() {
        service.startGame(players)
        val currentPlayerId = service.getCurrentState()!!.currentPlayerId!!

        val ex = assertThrows<IllegalStateException> {
            service.processAction(currentPlayerId, GameActionMessage(ActionType.DRAW_VISIBLE_ACTION_CARD))
        }
        assertTrue(ex.message!!.contains("actionCardIndex required"))
    }

    // ── processAction – START_NEXT_ROUND ──────────────────────────────────

    @Test
    fun `processAction START_NEXT_ROUND throws when phase is not ROUND_FINISHED`() {
        service.startGame(players)
        // Standardmäßig startet das Spiel in AWAITING_DRAW

        val ex = assertThrows<IllegalStateException> {
            service.processAction(player1Id, GameActionMessage(ActionType.START_NEXT_ROUND))
        }
        assertTrue(ex.message!!.contains("Cannot start next round right now"))
    }

    @Test
    fun `processAction START_NEXT_ROUND throws when non-host tries to start`() {
        service.startGame(players)
        // Setze die Phase manuell auf ROUND_FINISHED, um den ersten Check zu passieren
        val state = getInternalGameState(service).copy(phase = GamePhase.ROUND_FINISHED)
        setInternalGameState(service, state)

        // player2Id ist nicht der Host
        val ex = assertThrows<IllegalStateException> {
            service.processAction(player2Id, GameActionMessage(ActionType.START_NEXT_ROUND))
        }
        assertTrue(ex.message!!.contains("Only the host can start the next round"))
    }

    @Test
    fun `processAction START_NEXT_ROUND succeeds for host and starts new round`() {
        service.startGame(players)
        // Setze die Phase manuell auf ROUND_FINISHED, damit die Runde beendet ist
        val state = getInternalGameState(service).copy(phase = GamePhase.ROUND_FINISHED)
        setInternalGameState(service, state)

        // player1Id ist der Host (isHost = true)
        val result = service.processAction(player1Id, GameActionMessage(ActionType.START_NEXT_ROUND))

        // Überprüfe, ob die Runde inkrementiert wurde und das Board zurückgesetzt ist
        assertEquals(2, result.roundNumber)
        assertEquals(GamePhase.AWAITING_DRAW, result.phase)
        assertFalse(result.gameOver)
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
    fun `processAction DRAW from ACTION_DECK adds card to current player's action hand`() {
        service.startGame(players)
        val currentPlayerId = service.getCurrentState()!!.currentPlayerId!!

        val result = service.processAction(
            currentPlayerId,
            GameActionMessage(ActionType.DRAW, source = DrawSource.ACTION_DECK),
        )

        val player = result.players.first { it.playerId == currentPlayerId }
        assertEquals(1, player.actionCards.size)
        assertEquals(12, result.actionDrawPileCount)
        assertEquals(10, player.actionCards.single().value)
    }

    @Test
    fun `processAction DRAW_VISIBLE_ACTION_CARD adds selected visible card to current player's action hand`() {
        service.startGame(players)
        val stateBeforeDraw = service.getCurrentState()!!
        val currentPlayerId = stateBeforeDraw.currentPlayerId!!
        val visibleCard = stateBeforeDraw.visibleActionCards.first()

        val result = service.processAction(
            currentPlayerId,
            GameActionMessage(ActionType.DRAW_VISIBLE_ACTION_CARD, actionCardIndex = 0),
        )

        val player = result.players.first { it.playerId == currentPlayerId }
        assertEquals(visibleCard.id, player.actionCards.single().id)
        assertEquals(4, result.visibleActionCards.size)
        assertEquals(12, result.actionDrawPileCount)
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

    @Test
    fun `processAction DRAW_VISIBLE_ACTION_CARD adds selected card to player hand`() {
        service.startGame(players)
        val currentPlayerId = service.getCurrentState()!!.currentPlayerId!!
        val visibleCardId = service.getCurrentState()!!.visibleActionCards.first().id

        val result = service.processAction(
            currentPlayerId,
            GameActionMessage(ActionType.DRAW_VISIBLE_ACTION_CARD, actionCardIndex = 0),
        )

        val player = result.players.first { it.playerId == currentPlayerId }
        assertEquals(listOf(visibleCardId), player.actionCards.map { it.id })
        assertEquals(4, result.visibleActionCards.size)
    }

    @Test
    fun `processAction PLAY_ACTION_CARD consumes defense card and keeps the turn`() {
        service.startGame(players)
        val state = getInternalGameState(service)
        val currentPlayerId = state.currentPlayerId!!
        val updatedPlayers = state.players.mapIndexed { index, player ->
            if (index == state.currentPlayerIndex) {
                player.copy(actionCards = listOf(SkyjoCard.ActionCard.Defense(id = 999)))
            } else {
                player
            }
        }
        setInternalGameState(service, state.copy(players = updatedPlayers))

        val result = service.processAction(
            currentPlayerId,
            GameActionMessage(ActionType.PLAY_ACTION_CARD, actionCardIndex = 0),
        )

        val player = result.players.first { it.playerId == currentPlayerId }
        assertTrue(player.actionCards.isEmpty())
        assertEquals(currentPlayerId, result.currentPlayerId)
    }

    @Test
    fun `processAction DISCARD_ACTION_CARD removes card from player hand`() {
        service.startGame(players)
        val state = getInternalGameState(service)
        val currentPlayerId = state.currentPlayerId!!
        val updatedPlayers = state.players.mapIndexed { index, player ->
            if (index == state.currentPlayerIndex) {
                player.copy(actionCards = listOf(SkyjoCard.ActionCard.Defense(id = 1000)))
            } else {
                player
            }
        }
        setInternalGameState(service, state.copy(players = updatedPlayers))

        val result = service.processAction(
            currentPlayerId,
            GameActionMessage(ActionType.DISCARD_ACTION_CARD, actionCardIndex = 0),
        )

        val player = result.players.first { it.playerId == currentPlayerId }
        assertTrue(player.actionCards.isEmpty())
    }

    @Test
    fun `game updates expose action card kinds`() {
        service.startGame(players)
        val state = getInternalGameState(service)
        val updatedPlayers = state.players.mapIndexed { index, player ->
            if (index == state.currentPlayerIndex) {
                player.copy(
                    actionCards = listOf(
                        SkyjoCard.ActionCard.Defense(id = 999),
                        SkyjoCard.ActionCard.PlayerSwapCard(id = 1000),
                        SkyjoCard.ActionCard.SwapOwnCards(id = 1001),
                        SkyjoCard.ActionCard.DoubleTurn(id = 1002),
                        SkyjoCard.ActionCard.DrawThreeCards(id = 1003),
                    ),
                )
            } else {
                player
            }
        }
        setInternalGameState(service, state.copy(players = updatedPlayers))

        val update = service.getCurrentState()!!
        val currentPlayer = update.players.first { it.playerId == state.currentPlayerId }

        assertEquals(
            listOf(
                ActionCardKind.DEFENSE,
                ActionCardKind.PLAYER_SWAP,
                ActionCardKind.SWAP_OWN_CARDS,
                ActionCardKind.DOUBLE_TURN,
                ActionCardKind.DRAW_THREE_CARDS,
            ),
            currentPlayer.actionCards.map { it.kind },
        )
    }

    @Test
    fun `game updates filter placeholder action cards for compatibility`() {
        service.startGame(players)
        val state = getInternalGameState(service)
        val updatedPlayers = state.players.mapIndexed { index, player ->
            if (index == state.currentPlayerIndex) {
                player.copy(
                    actionCards = listOf(
                        SkyjoCard.ActionCard.Placeholder(id = 1000),
                        SkyjoCard.ActionCard.Defense(id = 1001),
                    ),
                )
            } else {
                player
            }
        }
        setInternalGameState(
            service,
            state.copy(
                players = updatedPlayers,
                visibleActionCards = listOf(
                    SkyjoCard.ActionCard.Placeholder(id = 1002),
                    SkyjoCard.ActionCard.Enlightenment(id = 1003),
                ),
            ),
        )

        val update = service.getCurrentState()!!
        val currentPlayer = update.players.first { it.playerId == state.currentPlayerId }

        assertEquals(listOf(ActionCardKind.DEFENSE), currentPlayer.actionCards.map { it.kind })
        assertEquals(listOf(ActionCardKind.ENLIGHTENMENT), update.visibleActionCards.map { it.kind })
    }

    @Test
    fun `processAction PLAY_ACTION_CARD performs player swap`() {
        service.startGame(players)
        val state = getInternalGameState(service)
        val currentPlayerId = state.currentPlayerId!!
        val updatedPlayers = state.players.mapIndexed { index, player ->
            if (index == state.currentPlayerIndex) {
                player.copy(actionCards = listOf(SkyjoCard.ActionCard.PlayerSwapCard(id = 1000)))
            } else {
                player
            }
        }
        setInternalGameState(service, state.copy(players = updatedPlayers))

        val otherPlayerId = state.players.first { it.id != currentPlayerId }.id
        val result = service.processAction(
            currentPlayerId,
            GameActionMessage(
                type = ActionType.PLAY_ACTION_CARD,
                actionCardIndex = 0,
                targetPlayer1Id = currentPlayerId,
                targetPlayer1Row = 0,
                targetPlayer1Col = 0,
                targetPlayer2Id = otherPlayerId,
                targetPlayer2Row = 0,
                targetPlayer2Col = 0,
            ),
        )

        assertNotEquals(currentPlayerId, result.currentPlayerId)
        assertTrue(result.players.first { it.playerId == currentPlayerId }.actionCards.isEmpty())
    }

    @Test
    fun `processAction PLAY_ACTION_CARD player swap is blocked by defense after being played`() {
        service.startGame(players)
        val state = getInternalGameState(service)
        val currentPlayerId = state.currentPlayerId!!
        val currentPlayerIndex = state.currentPlayerIndex
        val otherPlayerIndex = state.players.indices.first { it != currentPlayerIndex }
        val otherPlayerId = state.players[otherPlayerIndex].id
        val currentPosition = BoardPosition(0, 0)
        val otherPosition = BoardPosition(0, 0)
        val currentSlotBefore = state.players[currentPlayerIndex].board.slotAt(currentPosition)
        val otherSlotBefore = state.players[otherPlayerIndex].board.slotAt(otherPosition)
        val playerSwapCard = SkyjoCard.ActionCard.PlayerSwapCard(id = 1000)
        val defenseCard = SkyjoCard.ActionCard.Defense(id = 1001)
        val updatedPlayers = state.players.mapIndexed { index, player ->
            when (index) {
                currentPlayerIndex -> player.copy(actionCards = listOf(playerSwapCard))
                otherPlayerIndex -> player.copy(actionCards = listOf(defenseCard))
                else -> player
            }
        }
        setInternalGameState(service, state.copy(players = updatedPlayers))

        service.processAction(
            currentPlayerId,
            GameActionMessage(
                type = ActionType.PLAY_ACTION_CARD,
                actionCardIndex = 0,
                targetPlayer1Id = otherPlayerId,
                targetPlayer1Row = otherPosition.row,
                targetPlayer1Col = otherPosition.column,
                targetPlayer2Id = currentPlayerId,
                targetPlayer2Row = currentPosition.row,
                targetPlayer2Col = currentPosition.column,
            ),
        )

        val updatedState = getInternalGameState(service)
        assertEquals(currentSlotBefore, updatedState.players[currentPlayerIndex].board.slotAt(currentPosition))
        assertEquals(otherSlotBefore, updatedState.players[otherPlayerIndex].board.slotAt(otherPosition))
        assertTrue(updatedState.players[currentPlayerIndex].actionCards.isEmpty())
        assertTrue(updatedState.players[otherPlayerIndex].actionCards.isEmpty())
        assertEquals(listOf(playerSwapCard, defenseCard), updatedState.actionDiscardPile.cards.takeLast(2))
    }

    @Test
    fun `processAction PLAY_ACTION_CARD performs own card swap`() {
        service.startGame(players)
        val state = getInternalGameState(service)
        val currentPlayerId = state.currentPlayerId!!
        val pos1 = BoardPosition(0, 0)
        val pos2 = BoardPosition(0, 1)
        val slot1 = state.currentPlayer().board.slotAt(pos1) as BoardSlot.Occupied
        val slot2 = state.currentPlayer().board.slotAt(pos2) as BoardSlot.Occupied
        val updatedPlayers = state.players.mapIndexed { index, player ->
            if (index == state.currentPlayerIndex) {
                player.copy(actionCards = listOf(SkyjoCard.ActionCard.SwapOwnCards(id = 1001)))
            } else {
                player
            }
        }
        setInternalGameState(service, state.copy(players = updatedPlayers))

        val result = service.processAction(
            currentPlayerId,
            GameActionMessage(
                type = ActionType.PLAY_ACTION_CARD,
                actionCardIndex = 0,
                targetPlayer1Row = pos1.row,
                targetPlayer1Col = pos1.column,
                targetPlayer2Row = pos2.row,
                targetPlayer2Col = pos2.column,
            ),
        )

        val updatedState = getInternalGameState(service)
        val updatedPlayer = updatedState.players.first { it.id == currentPlayerId }
        val updatedSlot1 = updatedPlayer.board.slotAt(pos1) as BoardSlot.Occupied
        val updatedSlot2 = updatedPlayer.board.slotAt(pos2) as BoardSlot.Occupied

        assertEquals(slot2.card, updatedSlot1.card)
        assertEquals(slot2.faceUp, updatedSlot1.faceUp)
        assertEquals(slot1.card, updatedSlot2.card)
        assertEquals(slot1.faceUp, updatedSlot2.faceUp)
        assertTrue(result.players.first { it.playerId == currentPlayerId }.actionCards.isEmpty())
    }

    @Test
    fun `processAction PLAY_ACTION_CARD own swap requires first row`() {
        assertSwapOwnMissingFieldFails(
            action = validSwapOwnAction().copy(targetPlayer1Row = null),
            expectedMessage = "targetPlayer1Row required",
        )
    }

    @Test
    fun `processAction PLAY_ACTION_CARD own swap requires first column`() {
        assertSwapOwnMissingFieldFails(
            action = validSwapOwnAction().copy(targetPlayer1Col = null),
            expectedMessage = "targetPlayer1Col required",
        )
    }

    @Test
    fun `processAction PLAY_ACTION_CARD own swap requires second row`() {
        assertSwapOwnMissingFieldFails(
            action = validSwapOwnAction().copy(targetPlayer2Row = null),
            expectedMessage = "targetPlayer2Row required",
        )
    }

    @Test
    fun `processAction PLAY_ACTION_CARD own swap requires second column`() {
        assertSwapOwnMissingFieldFails(
            action = validSwapOwnAction().copy(targetPlayer2Col = null),
            expectedMessage = "targetPlayer2Col required",
        )
    }

    @Test
    fun `processAction PLAY_ACTION_CARD own swap rejects same position and keeps action card`() {
        service.startGame(players)
        val state = getInternalGameState(service)
        val currentPlayerId = state.currentPlayerId!!
        val actionCard = SkyjoCard.ActionCard.SwapOwnCards(id = 1001)
        val updatedPlayers = state.players.mapIndexed { index, player ->
            if (index == state.currentPlayerIndex) {
                player.copy(actionCards = listOf(actionCard))
            } else {
                player
            }
        }
        setInternalGameState(service, state.copy(players = updatedPlayers))

        val exception = assertThrows<InvalidMoveException> {
            service.processAction(
                currentPlayerId,
                GameActionMessage(
                    type = ActionType.PLAY_ACTION_CARD,
                    actionCardIndex = 0,
                    targetPlayer1Row = 0,
                    targetPlayer1Col = 0,
                    targetPlayer2Row = 0,
                    targetPlayer2Col = 0,
                ),
            )
        }

        assertTrue(exception.message!!.contains("cannot swap the same board position"))
        val unchangedState = getInternalGameState(service)
        assertEquals(listOf(actionCard), unchangedState.currentPlayer().actionCards)
        assertEquals(GamePhase.AWAITING_DRAW, unchangedState.phase)
    }

    @Test
    fun `processAction PLAY_ACTION_CARD own swap rejects unavailable board position and keeps action card`() {
        service.startGame(players)
        val state = getInternalGameState(service)
        val currentPlayerId = state.currentPlayerId!!
        val actionCard = SkyjoCard.ActionCard.SwapOwnCards(id = 1001)
        val updatedPlayers = state.players.mapIndexed { index, player ->
            if (index == state.currentPlayerIndex) {
                player.copy(actionCards = listOf(actionCard))
            } else {
                player
            }
        }
        setInternalGameState(service, state.copy(players = updatedPlayers))

        val exception = assertThrows<InvalidMoveException> {
            service.processAction(
                currentPlayerId,
                validSwapOwnAction().copy(targetPlayer1Row = 99),
            )
        }

        assertTrue(exception.message!!.contains("row must be between"))
        assertEquals(listOf(actionCard), getInternalGameState(service).currentPlayer().actionCards)
    }

    @Test
    fun `processAction PLAY_ACTION_CARD rejects unavailable action card index`() {
        service.startGame(players)
        val state = getInternalGameState(service)
        val currentPlayerId = state.currentPlayerId!!
        val updatedPlayers = state.players.mapIndexed { index, player ->
            if (index == state.currentPlayerIndex) {
                player.copy(actionCards = listOf(SkyjoCard.ActionCard.PlayerSwapCard(id = 1000)))
            } else {
                player
            }
        }
        setInternalGameState(service, state.copy(players = updatedPlayers))

        val exception = assertThrows<IllegalStateException> {
            service.processAction(
                currentPlayerId,
                GameActionMessage(ActionType.PLAY_ACTION_CARD, actionCardIndex = 3),
            )
        }

        assertTrue(exception.message!!.contains("action card index 3 is not available"))
    }

    @Test
    fun `processAction PLAY_ACTION_CARD rejects placeholder action card`() {
        service.startGame(players)
        val state = getInternalGameState(service)
        val currentPlayerId = state.currentPlayerId!!
        val updatedPlayers = state.players.mapIndexed { index, player ->
            if (index == state.currentPlayerIndex) {
                player.copy(actionCards = listOf(SkyjoCard.ActionCard.Placeholder(id = 1000)))
            } else {
                player
            }
        }
        setInternalGameState(service, state.copy(players = updatedPlayers))

        val exception = assertThrows<InvalidMoveException> {
            service.processAction(
                currentPlayerId,
                GameActionMessage(ActionType.PLAY_ACTION_CARD, actionCardIndex = 0),
            )
        }

        assertTrue(exception.message!!.contains("placeholder action cards cannot be played"))
        assertEquals(
            listOf(SkyjoCard.ActionCard.Placeholder(id = 1000)),
            getInternalGameState(service).currentPlayer().actionCards,
        )
    }

    @Test
    fun `processAction PLAY_ACTION_CARD player swap requires first player id`() {
        assertPlayerSwapMissingFieldFails(
            action = validPlayerSwapAction().copy(targetPlayer1Id = null),
            expectedMessage = "targetPlayer1Id required",
        )
    }

    @Test
    fun `processAction PLAY_ACTION_CARD player swap requires first player row`() {
        assertPlayerSwapMissingFieldFails(
            action = validPlayerSwapAction().copy(targetPlayer1Row = null),
            expectedMessage = "targetPlayer1Row required",
        )
    }

    @Test
    fun `processAction PLAY_ACTION_CARD player swap requires first player col`() {
        assertPlayerSwapMissingFieldFails(
            action = validPlayerSwapAction().copy(targetPlayer1Col = null),
            expectedMessage = "targetPlayer1Col required",
        )
    }

    @Test
    fun `processAction PLAY_ACTION_CARD player swap requires second player id`() {
        assertPlayerSwapMissingFieldFails(
            action = validPlayerSwapAction().copy(targetPlayer2Id = null),
            expectedMessage = "targetPlayer2Id required",
        )
    }

    @Test
    fun `processAction PLAY_ACTION_CARD player swap requires second player row`() {
        assertPlayerSwapMissingFieldFails(
            action = validPlayerSwapAction().copy(targetPlayer2Row = null),
            expectedMessage = "targetPlayer2Row required",
        )
    }

    @Test
    fun `processAction PLAY_ACTION_CARD player swap requires second player col`() {
        assertPlayerSwapMissingFieldFails(
            action = validPlayerSwapAction().copy(targetPlayer2Col = null),
            expectedMessage = "targetPlayer2Col required",
        )
    }

    private fun assertPlayerSwapMissingFieldFails(action: GameActionMessage, expectedMessage: String) {
        service.startGame(players)
        val state = getInternalGameState(service)
        val currentPlayerId = state.currentPlayerId!!
        val updatedPlayers = state.players.mapIndexed { index, player ->
            if (index == state.currentPlayerIndex) {
                player.copy(actionCards = listOf(SkyjoCard.ActionCard.PlayerSwapCard(id = 1000)))
            } else {
                player
            }
        }
        setInternalGameState(service, state.copy(players = updatedPlayers))

        val exception = assertThrows<IllegalStateException> {
            service.processAction(currentPlayerId, action)
        }

        assertTrue(exception.message!!.contains(expectedMessage))
    }

    private fun validPlayerSwapAction(): GameActionMessage =
        GameActionMessage(
            type = ActionType.PLAY_ACTION_CARD,
            actionCardIndex = 0,
            targetPlayer1Id = player1Id,
            targetPlayer1Row = 0,
            targetPlayer1Col = 0,
            targetPlayer2Id = player2Id,
            targetPlayer2Row = 0,
            targetPlayer2Col = 0,
        )

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
        assertTrue(result.gameUpdate.players.first { it.playerId == player1Id }.actionCards.isEmpty())

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
    fun `playActionCard Enlightenment can inspect another player's hidden row privately`() {
        val targetRow = 0
        val otherBoard = playerBoardWithValues(
            mapOf(
                BoardPosition(targetRow, 0) to 3,
                BoardPosition(targetRow, 1) to 6,
                BoardPosition(targetRow, 2) to 9,
                BoardPosition(targetRow, 3) to 12,
            ),
            faceUp = false,
        )
        setInternalGameState(
            service,
            gameStateWithActionCard(
                playerId = player1Id,
                board = playerBoardWithValues(emptyMap()),
                otherBoard = otherBoard,
            ),
        )

        val result = service.playActionCard(
            player1Id,
            PlayActionCardCommand(
                actionCardIndex = 0,
                parameters = ActionCardParameters.BoardLineTarget(
                    targetPlayerId = player2Id,
                    targetType = BoardLineTargetType.ROW,
                    lineIndex = targetRow,
                ),
            ),
        )

        val privateResult = result.privateActionCardResults[player1Id]!!
        assertEquals(setOf(player1Id), result.privateActionCardResults.keys)
        assertEquals(player2Id, privateResult.targetPlayerId)
        assertEquals(listOf(3, 6, 9, 12), privateResult.inspectedValues)

        val publicRow = result.gameUpdate.players.first { it.playerId == player2Id }.board[targetRow]
        assertTrue(publicRow.all { it.faceUp == false })
        assertTrue(publicRow.all { it.card == null })
    }

    @Test
    fun `handleRoundFinished accumulates scores into totalScores`() {
        service.startGame(players, GameConfig(maxRounds = 2, targetScore = 1000))

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
    fun `handleRoundFinished stays in ROUND_FINISHED when maxRounds not reached`() {
        service.startGame(players, GameConfig(maxRounds = 2, targetScore = 1000))
        val gameState = getInternalGameState(service)
        val currentPlayerId = gameState.currentPlayerId!!
        val finishedState = engine.finishRound(gameState.copy(finisherPlayerId = currentPlayerId))

        val result = service.handleRoundFinished(finishedState)

        // Die Phase muss nun ROUND_FINISHED sein, da wir auf den Host warten
        assertEquals(GamePhase.ROUND_FINISHED, result.phase)

        // Die Rundennummer wird erst im START_NEXT_ROUND Block erhöht, bleibt hier also 1
        assertEquals(1, result.roundNumber)

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
    fun `completed game is not active or rejoinable`() {
        val dataSource = SingleConnectionDataSource("jdbc:sqlite::memory:", true)
        val jdbc = JdbcTemplate(dataSource)
        val repository = GameRepository(jdbc)
        repository.initSchema()
        service = GameService(engine, repository)
        service.startGame(players, GameConfig(maxRounds = 1))
        val gameState = getInternalGameState(service)
        val finishedState = engine.finishRound(gameState.copy(finisherPlayerId = gameState.currentPlayerId!!))

        val result = service.handleRoundFinished(finishedState)

        assertTrue(result.gameOver)
        assertNull(repository.getPlayerGame(player1Id))
        assertNull(service.getActiveGameId())
        assertNull(service.reconnectPlayer(player1Id, "Alice", result.gameId!!))
        assertNull(service.getCurrentState(player1Id))
        assertNull(GameService(engine, repository).getCurrentState(player1Id))
    }

    @Test
    fun `round number and scores are restored after a restart`() {
        val dataSource = SingleConnectionDataSource("jdbc:sqlite::memory:", true)
        val jdbc = JdbcTemplate(dataSource)
        val repository = GameRepository(jdbc)
        repository.initSchema()
        service = GameService(engine, repository)
        service.startGame(players, GameConfig(maxRounds = 5, targetScore = 1000))

        // Finish round 1 (accumulates scores) without ending the game, then start round 2.
        val roundOne = getInternalGameState(service)
        val finished = engine.finishRound(roundOne.copy(finisherPlayerId = roundOne.currentPlayerId!!))
        val afterRound = service.handleRoundFinished(finished)
        assertFalse(afterRound.gameOver)
        val nextRound = service.processAction(player1Id, GameActionMessage(ActionType.START_NEXT_ROUND))
        assertEquals(2, nextRound.roundNumber)
        val expectedScores = nextRound.totalScores.associate { it.playerId to it.totalScore }

        // Simulate a server restart: a fresh GameService over the same repository.
        val restored = GameService(engine, repository).getCurrentState(player1Id)!!

        assertEquals(2, restored.roundNumber)
        assertEquals(expectedScores, restored.totalScores.associate { it.playerId to it.totalScore })
    }

    @Test
    fun `completed lobby game works when no lobby service is configured`() {
        service = GameService(engine, null)
        service.startGame("lobby-1", players, GameConfig(maxRounds = 1))
        val gameState = getInternalGameState(service)
        val finishedState = engine.finishRound(gameState.copy(finisherPlayerId = gameState.currentPlayerId!!))

        val result = service.handleRoundFinished(finishedState)

        assertTrue(result.gameOver)
        assertEquals("lobby-1", result.lobbyId)
        assertNull(service.getCurrentState(player1Id))
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

    @Test
    fun `handleRoundFinished records final stats once when game is over`() {
        val dataSource = SingleConnectionDataSource("jdbc:sqlite::memory:", true)
        val jdbc = JdbcTemplate(dataSource)
        val authRepository = AuthRepository(jdbc)
        val statsRepository = StatsRepository(jdbc)
        authRepository.initSchema()
        statsRepository.initSchema()
        authRepository.createUser(player1Id, "Alice", "hash-1", now = 1L)
        authRepository.createUser(player2Id, "Bob", "hash-2", now = 1L)
        val statsService = StatsService(statsRepository, authRepository, nowProvider = { 1_000L })
        service = GameService(engine, gameRepository = null, statsService = statsService)
        service.startGame(players, GameConfig(maxRounds = 1))
        val gameState = getInternalGameState(service)
        val finishedState = engine.finishRound(gameState.copy(finisherPlayerId = gameState.currentPlayerId!!))

        service.handleRoundFinished(finishedState)
        service.handleRoundFinished(finishedState)

        assertEquals(1, statsRepository.findStats(player1Id)?.gamesPlayed)
        assertEquals(1, statsRepository.findStats(player2Id)?.gamesPlayed)
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

    private fun assertSwapOwnMissingFieldFails(
        action: GameActionMessage,
        expectedMessage: String,
    ) {
        service.startGame(players)
        val state = getInternalGameState(service)
        val currentPlayerId = state.currentPlayerId!!
        val actionCard = SkyjoCard.ActionCard.SwapOwnCards(id = 1001)
        val updatedPlayers = state.players.mapIndexed { index, player ->
            if (index == state.currentPlayerIndex) {
                player.copy(actionCards = listOf(actionCard))
            } else {
                player
            }
        }
        setInternalGameState(service, state.copy(players = updatedPlayers))

        val exception = assertThrows<InvalidMoveException> {
            service.processAction(currentPlayerId, action)
        }

        assertTrue(exception.message!!.contains(expectedMessage))
        assertEquals(listOf(actionCard), getInternalGameState(service).currentPlayer().actionCards)
    }
}

// Helpers to access internal state for test setup, backed by GameService's
// test-only seams (the previous reflection into private legacy fields was
// removed together with the shadow state).
private fun getInternalGameState(service: GameService): GameState =
    service.currentGameStateForTest()

private fun setInternalGameState(service: GameService, state: GameState) {
    service.seedGameForTest(state)
}

private fun gameStateWithActionCard(
    playerId: String,
    board: PlayerBoard,
    otherBoard: PlayerBoard = playerBoardWithValues(emptyMap()),
): GameState {
    val currentPlayer = PlayerState(
        id = playerId,
        board = board,
        actionCards = listOf(SkyjoCard.ActionCard.Enlightenment(id = 151)),
    )
    val otherPlayer = PlayerState(
        id = "player2",
        board = otherBoard,
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

private fun validSwapOwnAction() = GameActionMessage(
    type = ActionType.PLAY_ACTION_CARD,
    actionCardIndex = 0,
    targetPlayer1Row = 0,
    targetPlayer1Col = 0,
    targetPlayer2Row = 0,
    targetPlayer2Col = 1,
)
