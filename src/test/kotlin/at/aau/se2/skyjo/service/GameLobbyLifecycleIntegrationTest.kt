package at.aau.se2.skyjo.service

import at.aau.se2.skyjo.game.model.DrawSource
import at.aau.se2.skyjo.model.ActionType
import at.aau.se2.skyjo.model.GameActionMessage
import at.aau.se2.skyjo.model.GameConfig
import at.aau.se2.skyjo.model.GameUpdateMessage
import at.aau.se2.skyjo.model.SlotType
import at.aau.se2.skyjo.model.lobby.LobbyStatus
import at.aau.se2.skyjo.persistence.AuthRepository
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
    private lateinit var authService: AuthService

    @Autowired
    private lateinit var authRepository: AuthRepository

    @Test
    fun `game over closes source lobby so players are not returned to it`() {
        val alice = authService.register("LifecycleAlice", "password-a").user
        val bob = authService.register("LifecycleBob", "password-b").user
        val lobby = lobbyService.createLobby(alice)
        val lobbyId = lobby.lobbyId!!
        authService.markUserConnected(alice.userId, lobbyId)
        lobbyService.joinLobby(bob, lobby.joinCode!!)
        authService.markUserConnected(bob.userId, lobbyId)
        val inGameLobby = lobbyService.startGame(userId = alice.userId, lobbyId = lobbyId)

        val result = playUntilGameOver(
            gameService.startGame(lobbyId, inGameLobby.players, GameConfig(maxRounds = 1))
        )

        assertTrue(result.gameOver)
        assertNull(lobbyService.getCurrentLobbyForUser(alice.userId))
        assertEquals(LobbyStatus.CLOSED, lobbyService.getLobbyById(lobbyId)?.status)
        assertNull(authRepository.getPresence(alice.userId)?.currentLobbyId)
        assertNull(authRepository.getPresence(bob.userId)?.currentLobbyId)
        assertTrue(authRepository.getPresence(alice.userId)?.connected == true)
        assertTrue(authRepository.getPresence(bob.userId)?.connected == true)
    }

    private fun playUntilGameOver(initialUpdate: GameUpdateMessage): GameUpdateMessage {
        var update = initialUpdate
        var turnCount = 0
        while (!update.gameOver && turnCount < MAX_TURNS_TO_FINISH_ROUND) {
            val currentPlayerId = requireNotNull(update.currentPlayerId) { "current player is missing" }
            update = gameService.processAction(
                currentPlayerId,
                GameActionMessage(ActionType.DRAW, source = DrawSource.DECK),
            )
            val position = firstFaceDownPosition(update, currentPlayerId)
            update = if (position != null) {
                gameService.processAction(
                    currentPlayerId,
                    GameActionMessage(ActionType.DISCARD_AND_REVEAL, row = position.first, col = position.second),
                )
            } else {
                gameService.processAction(
                    currentPlayerId,
                    GameActionMessage(ActionType.REPLACE, row = 0, col = 0),
                )
            }
            turnCount += 1
        }
        return update
    }

    private fun firstFaceDownPosition(update: GameUpdateMessage, playerId: String): Pair<Int, Int>? {
        val board = update.players.first { it.playerId == playerId }.board
        board.forEachIndexed { rowIndex, row ->
            row.forEachIndexed { colIndex, slot ->
                if (slot.type == SlotType.OCCUPIED && slot.faceUp == false) {
                    return rowIndex to colIndex
                }
            }
        }
        return null
    }

    private companion object {
        const val MAX_TURNS_TO_FINISH_ROUND = 50
    }
}
