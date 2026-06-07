package at.aau.se2.skyjo.service

import at.aau.se2.skyjo.game.model.GameState
import at.aau.se2.skyjo.game.service.SkyjoEngine
import at.aau.se2.skyjo.model.GameConfig
import at.aau.se2.skyjo.model.auth.AuthUserDto
import at.aau.se2.skyjo.model.lobby.LobbyStatus
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.test.annotation.DirtiesContext

@SpringBootTest
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_EACH_TEST_METHOD)
class GameLobbyLifecycleIntegrationTest {

    @Autowired
    private lateinit var lobbyService: LobbyService

    @Autowired
    private lateinit var gameService: GameService

    @Autowired
    private lateinit var engine: SkyjoEngine

    @Test
    fun `game over closes source lobby so players are not returned to it`() {
        val lobby = lobbyService.createLobby(user("lifecycle-user-a", "Alice"))
        val lobbyId = lobby.lobbyId!!
        lobbyService.joinLobby(user("lifecycle-user-b", "Bob"), lobby.joinCode!!)
        val inGameLobby = lobbyService.startGame(userId = "lifecycle-user-a", lobbyId = lobbyId)
        gameService.startGame(lobbyId, inGameLobby.players, GameConfig(maxRounds = 1))
        val gameState = getInternalGameState(gameService)
        val finishedState = engine.finishRound(gameState.copy(finisherPlayerId = gameState.currentPlayerId!!))

        val result = gameService.handleRoundFinished(finishedState)

        assertTrue(result.gameOver)
        assertNull(lobbyService.getCurrentLobbyForUser("lifecycle-user-a"))
        assertEquals(LobbyStatus.CLOSED, lobbyService.getLobbyById(lobbyId)?.status)
    }

    private fun user(id: String, username: String) = AuthUserDto(userId = id, username = username)

    private fun getInternalGameState(service: GameService): GameState {
        val field = GameService::class.java.getDeclaredField("gameState")
        field.isAccessible = true
        return field.get(service) as GameState
    }
}
