package at.aau.se2.skyjo.persistence

import at.aau.se2.skyjo.game.model.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.datasource.SingleConnectionDataSource

class GameRepositoryTest {

    private lateinit var repo: GameRepository

    @BeforeEach
    fun setUp() {
        val dataSource = SingleConnectionDataSource("jdbc:sqlite::memory:", true)
        val jdbc = JdbcTemplate(dataSource)
        repo = GameRepository(jdbc)
        repo.initSchema()
    }

    @Test
    fun `initSchema is idempotent`() {
        repo.initSchema()
        repo.initSchema()
    }

    @Test
    fun `loadActiveGame returns null when no game saved`() {
        assertNull(repo.loadActiveGame())
    }

    @Test
    fun `saveGame and loadActiveGame roundtrip simple state`() {
        val state = GameState(phase = GamePhase.AWAITING_DRAW)
        repo.saveGame("game-1", state)

        val loaded = repo.loadActiveGame()

        assertNotNull(loaded)
        assertEquals("game-1", loaded!!.first)
        assertEquals(GamePhase.AWAITING_DRAW, loaded.second.phase)
    }

    @Test
    fun `saveGame and loadActiveGames return multiple active games with lobby ids`() {
        repo.saveGame("game-1", "lobby-1", GameState(phase = GamePhase.AWAITING_DRAW))
        repo.saveGame("game-2", "lobby-2", GameState(phase = GamePhase.AWAITING_REPLACEMENT))
        repo.saveGame("game-3", "lobby-3", GameState(phase = GamePhase.NOT_STARTED))
        // game-4 IST JETZT AKTIV!
        repo.saveGame("game-4", "lobby-4", GameState(phase = GamePhase.ROUND_FINISHED))
        repo.saveGame("game-5", "lobby-5", GameState(phase = GamePhase.AWAITING_DRAW), completed = true)

        val loaded = repo.loadActiveGames()

        // NEU: game-4 ist jetzt Teil der aktiven Spiele!
        assertEquals(setOf("game-1", "game-2", "game-4"), loaded.map { it.gameId }.toSet())
        assertEquals("lobby-1", loaded.first { it.gameId == "game-1" }.lobbyId)
        assertEquals(GamePhase.AWAITING_REPLACEMENT, loaded.first { it.gameId == "game-2" }.state.phase)
        assertEquals(GamePhase.ROUND_FINISHED, loaded.first { it.gameId == "game-4" }.state.phase)
    }

    @Test
    fun `loadActiveGame ignores NOT_STARTED games`() {
        val state = GameState(phase = GamePhase.NOT_STARTED)
        repo.saveGame("game-not-started", state)

        assertNull(repo.loadActiveGame())
    }

    @Test
    fun `loadActiveGame includes ROUND_FINISHED games`() {
        val state = GameState(phase = GamePhase.ROUND_FINISHED)
        repo.saveGame("game-finished-round", state)

        // NEU: Das Spiel darf nicht mehr ignoriert werden
        val loaded = repo.loadActiveGame()
        assertNotNull(loaded)
        assertEquals("game-finished-round", loaded!!.first)
    }

    @Test
    fun `saveGame overwrites existing game`() {
        val stateV1 = GameState(phase = GamePhase.AWAITING_DRAW)
        val stateV2 = GameState(phase = GamePhase.AWAITING_REPLACEMENT)
        repo.saveGame("game-1", stateV1)
        repo.saveGame("game-1", stateV2)

        val loaded = repo.loadActiveGame()

        assertNotNull(loaded)
        assertEquals(GamePhase.AWAITING_REPLACEMENT, loaded!!.second.phase)
    }

    @Test
    fun `saveGame and loadActiveGame roundtrip with player board`() {
        val cards = BoardLayout.POSITIONS.mapIndexed { i, _ ->
            SkyjoCard.NumberCard(i + 1, i - 3)
        }
        val board = PlayerBoard.fromCards(cards, setOf(BoardPosition(0, 0), BoardPosition(0, 1)))
        val player = PlayerState(id = "p1", board = board)
        val state = GameState(
            players = listOf(player),
            phase = GamePhase.AWAITING_DRAW,
            currentPlayerIndex = 0,
        )

        repo.saveGame("game-board", state)
        val loaded = repo.loadActiveGame()

        assertNotNull(loaded)
        assertEquals(1, loaded!!.second.players.size)
        assertEquals("p1", loaded.second.players[0].id)
        assertEquals(12, loaded.second.players[0].board.slots.size)
    }

    @Test
    fun `saveGame and loadActiveGame roundtrip with draw pile`() {
        val drawCards = listOf(SkyjoCard.NumberCard(1, 5), SkyjoCard.NumberCard(2, -2))
        val state = GameState(
            phase = GamePhase.AWAITING_DRAW,
            drawPile = DrawPile(drawCards),
        )

        repo.saveGame("game-pile", state)
        val loaded = repo.loadActiveGame()

        assertNotNull(loaded)
        assertEquals(2, loaded!!.second.drawPile.size)
    }

    @Test
    fun `savePlayerSession creates new session`() {
        repo.saveGame("game-1", GameState(phase = GamePhase.AWAITING_DRAW))
        repo.savePlayerSession("player-1", "game-1", connected = true)

        assertEquals("game-1", repo.getPlayerGame("player-1"))
    }

    @Test
    fun `savePlayerSession updates existing session`() {
        repo.saveGame("game-1", GameState(phase = GamePhase.AWAITING_DRAW))
        repo.saveGame("game-2", GameState(phase = GamePhase.AWAITING_REPLACEMENT))
        repo.savePlayerSession("player-1", "game-1", connected = true)
        repo.savePlayerSession("player-1", "game-2", connected = false)

        assertEquals("game-2", repo.getPlayerGame("player-1"))
    }

    @Test
    fun `getPlayerGame returns null for unknown player`() {
        assertNull(repo.getPlayerGame("unknown"))
    }

    @Test
    fun `markDisconnected updates existing session`() {
        repo.saveGame("game-1", GameState(phase = GamePhase.AWAITING_DRAW))
        repo.savePlayerSession("player-1", "game-1", connected = true)
        repo.markDisconnected("player-1")

        assertEquals("game-1", repo.getPlayerGame("player-1"))
    }

    @Test
    fun `getPlayerGame ignores inactive games but includes ROUND_FINISHED`() {
        repo.saveGame("game-not-started", GameState(phase = GamePhase.NOT_STARTED))
        repo.saveGame("game-round-finished", GameState(phase = GamePhase.ROUND_FINISHED))
        repo.saveGame("game-completed", GameState(phase = GamePhase.AWAITING_DRAW), completed = true)

        repo.savePlayerSession("player-1", "game-not-started", connected = true)
        repo.savePlayerSession("player-2", "game-round-finished", connected = true)
        repo.savePlayerSession("player-3", "game-completed", connected = true)
        repo.savePlayerSession("player-4", "game-missing", connected = true)

        assertNull(repo.getPlayerGame("player-1"))
        // NEU: player-2 befindet sich in einer pausierten, aber aktiven Runde!
        assertEquals("game-round-finished", repo.getPlayerGame("player-2"))
        assertNull(repo.getPlayerGame("player-3"))
        assertNull(repo.getPlayerGame("player-4"))
    }

    @Test
    fun `deletePlayerSessionsForGame removes completed game sessions`() {
        repo.saveGame("game-1", GameState(phase = GamePhase.AWAITING_DRAW))
        repo.saveGame("game-2", GameState(phase = GamePhase.AWAITING_DRAW))
        repo.savePlayerSession("player-1", "game-1", connected = true)
        repo.savePlayerSession("player-2", "game-1", connected = true)
        repo.savePlayerSession("player-3", "game-2", connected = true)

        repo.deletePlayerSessionsForGame("game-1")

        assertNull(repo.getPlayerGame("player-1"))
        assertNull(repo.getPlayerGame("player-2"))
        assertEquals("game-2", repo.getPlayerGame("player-3"))
    }

    @Test
    fun `markDisconnected on unknown player does nothing`() {
        repo.markDisconnected("nobody")
    }
}
