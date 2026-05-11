package at.aau.se2.skyjo.game.service
import at.aau.se2.skyjo.game.model.*
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class SkyjoGameServiceTest {
    private val engine: SkyjoEngine = mockk()
    private lateinit var service: SkyjoGameService

    @BeforeEach
    fun setUp() {
        service = SkyjoGameService(engine)
    }

    @Test
    fun startGameGivesGameState() {
        val playerIds = listOf("player1", "player2")
        val initialReveals = emptyMap<String, Set<BoardPosition>>()
        val seed = 42L

        // Nach dem Start wartet das Spiel darauf, dass der erste Spieler zieht
        val expectedState = GameState(phase = GamePhase.AWAITING_DRAW)

        every { engine.startGame(playerIds, initialReveals, seed) } returns expectedState

        val result = service.startGame(playerIds, initialReveals, seed)

        assertEquals(expectedState, result)
        assertEquals(expectedState, service.getGameState())
        verify(exactly = 1) { engine.startGame(playerIds, initialReveals, seed) }
    }

    @Test
    fun getGameStateReturnsZero() {
        // Ohne vorheriges startGame() ist der State standardmäßig NOT_STARTED
        val result = service.getGameState()
        assertNull(result)
    }

    @Test
    fun drawFromDeckReturnsState() {
        val stateBeforeDraw = GameState(phase = GamePhase.AWAITING_DRAW)
        // Nach dem Ziehen vom Stapel muss der Spieler die Karte tauschen oder abwerfen
        val stateAfterDraw = GameState(phase = GamePhase.AWAITING_REPLACEMENT)

        every { engine.startGame(any(), any(), any()) } returns stateBeforeDraw
        service.startGame(emptyList(), emptyMap())

        every { engine.drawFromDeck(stateBeforeDraw) } returns stateAfterDraw

        val result = service.drawFromDeck()

        assertEquals(stateAfterDraw, result)
        assertEquals(stateAfterDraw, service.getGameState())
    }

    @Test
    fun discardDrawnCardAndRevealReturnsNewState() {
        // Der Spieler hat eine Karte gezogen und muss nun agieren
        val stateBeforeDiscard = GameState(phase = GamePhase.AWAITING_REPLACEMENT)

        // Nach dem Abwerfen ist regulär der nächste Spieler dran
        val stateAfterDiscard = GameState(phase = GamePhase.AWAITING_DRAW)

        val position = mockk<BoardPosition>()

        every { engine.startGame(any(), any(), any()) } returns stateBeforeDiscard
        service.startGame(emptyList(), emptyMap())

        every { engine.discardDrawnCardAndReveal(stateBeforeDiscard, position) } returns stateAfterDiscard

        val result = service.discardDrawnCardAndReveal(position)

        assertEquals(stateAfterDiscard, result)
        verify { engine.discardDrawnCardAndReveal(stateBeforeDiscard, position) }
    }

    @Test
    fun takeDiscardCardReturnsNewStateAndOneCard() {
        // Der Spieler ist am Zug und entscheidet sich, die offene Karte vom Ablagestapel zu nehmen.
        val stateBeforeTake = GameState(phase = GamePhase.AWAITING_DRAW)

        // Nachdem er die Karte vom Ablagestapel genommen hat, MUSS er sie gegen eine
        // eigene Karte auf dem Feld tauschen (er darf sie nicht einfach abwerfen).
        val stateAfterTake = GameState(phase = GamePhase.AWAITING_REPLACEMENT)

        every { engine.startGame(any(), any(), any()) } returns stateBeforeTake
        service.startGame(emptyList(), emptyMap())

        every { engine.takeDiscardCard(stateBeforeTake) } returns stateAfterTake

        val result = service.takeDiscardCard()

        assertEquals(stateAfterTake, result)
        assertEquals(stateAfterTake, service.getGameState())
        verify(exactly = 1) { engine.takeDiscardCard(stateBeforeTake) }
    }

    @Test
    fun replaceDrawnCardGivesPositionGetsGameState() {
        // Der Spieler hat bereits eine Karte in der Hand (vom Deck oder Ablagestapel)
        // und wartet darauf, sie auf dem Feld zu platzieren.
        val stateBeforeReplace = GameState(phase = GamePhase.AWAITING_REPLACEMENT)

        // Nachdem die Karte getauscht wurde, ist regulär der nächste Spieler dran.
        val stateAfterReplace = GameState(phase = GamePhase.AWAITING_DRAW)

        val position = mockk<BoardPosition>()

        every { engine.startGame(any(), any(), any()) } returns stateBeforeReplace
        service.startGame(emptyList(), emptyMap())

        every { engine.replaceDrawnCard(stateBeforeReplace, position) } returns stateAfterReplace

        val result = service.replaceDrawnCard(position)

        assertEquals(stateAfterReplace, result)
        assertEquals(stateAfterReplace, service.getGameState())
        verify(exactly = 1) { engine.replaceDrawnCard(stateBeforeReplace, position) }
    }

    @Test
    fun drawVisibleActionCardReturnsNewState() {
        val stateBeforeDraw = GameState(phase = GamePhase.AWAITING_DRAW)
        val stateAfterDraw = GameState(phase = GamePhase.AWAITING_DRAW, visibleActionCards = listOf(actionCard(151)))

        every { engine.startGame(any(), any(), any()) } returns stateBeforeDraw
        service.startGame(emptyList(), emptyMap())
        every { engine.drawVisibleActionCard(stateBeforeDraw, 0) } returns stateAfterDraw

        val result = service.drawVisibleActionCard(0)

        assertEquals(stateAfterDraw, result)
        assertEquals(stateAfterDraw, service.getGameState())
        verify(exactly = 1) { engine.drawVisibleActionCard(stateBeforeDraw, 0) }
    }

    @Test
    fun drawActionCardFromDeckReturnsNewState() {
        val stateBeforeDraw = GameState(phase = GamePhase.AWAITING_DRAW)
        val stateAfterDraw = GameState(phase = GamePhase.AWAITING_DRAW, actionDrawPile = ActionDrawPile.empty())

        every { engine.startGame(any(), any(), any()) } returns stateBeforeDraw
        service.startGame(emptyList(), emptyMap())
        every { engine.drawActionCardFromDeck(stateBeforeDraw) } returns stateAfterDraw

        val result = service.drawActionCardFromDeck()

        assertEquals(stateAfterDraw, result)
        assertEquals(stateAfterDraw, service.getGameState())
        verify(exactly = 1) { engine.drawActionCardFromDeck(stateBeforeDraw) }
    }

    @Test
    fun discardActionCardReturnsNewState() {
        val stateBeforeDiscard = GameState(phase = GamePhase.AWAITING_DRAW)
        val stateAfterDiscard = GameState(phase = GamePhase.AWAITING_DRAW, actionDiscardPile = ActionDiscardPile(listOf(actionCard(151))))

        every { engine.startGame(any(), any(), any()) } returns stateBeforeDiscard
        service.startGame(emptyList(), emptyMap())
        every { engine.discardActionCard(stateBeforeDiscard, 0) } returns stateAfterDiscard

        val result = service.discardActionCard(0)

        assertEquals(stateAfterDiscard, result)
        assertEquals(stateAfterDiscard, service.getGameState())
        verify(exactly = 1) { engine.discardActionCard(stateBeforeDiscard, 0) }
    }

    @Test
    fun playActionCardReturnsNewState() {
        val stateBeforePlay = GameState(phase = GamePhase.AWAITING_DRAW)
        val stateAfterPlay = GameState(phase = GamePhase.AWAITING_DRAW, actionDiscardPile = ActionDiscardPile(listOf(actionCard(151))))
        val command = PlayActionCardCommand(actionCardIndex = 0)

        every { engine.startGame(any(), any(), any()) } returns stateBeforePlay
        service.startGame(emptyList(), emptyMap())
        every { engine.playActionCard(stateBeforePlay, command) } returns stateAfterPlay

        val result = service.playActionCard(command)

        assertEquals(stateAfterPlay, result)
        assertEquals(stateAfterPlay, service.getGameState())
        verify(exactly = 1) { engine.playActionCard(stateBeforePlay, command) }
    }

    private fun actionCard(id: Int) = SkyjoCard.ActionCard.Placeholder(id)
}
