package at.aau.se2.skyjo.controller
import at.aau.se2.skyjo.model.auth.AuthUserDto
import io.mockk.*
import io.mockk.impl.annotations.MockK
import io.mockk.impl.annotations.RelaxedMockK
import io.mockk.junit5.MockKExtension
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.springframework.messaging.simp.SimpMessageHeaderAccessor
import org.springframework.messaging.simp.SimpMessageSendingOperations
import java.security.Principal
import at.aau.se2.skyjo.service.*
import at.aau.se2.skyjo.model.*
import  at.aau.se2.skyjo.model.lobby.*

@ExtendWith(MockKExtension::class)
class LobbyControllerTest {

    @MockK
    lateinit var lobbyService: LobbyService

    @MockK
    lateinit var gameService: GameService

    @RelaxedMockK
    lateinit var messagingTemplate: SimpMessageSendingOperations

    private lateinit var controller: LobbyController

    @MockK
    lateinit var headerAccessor: SimpMessageHeaderAccessor

    @MockK
    lateinit var principal: Principal

    @MockK
    lateinit var authSupport: AuthSupport

    private val playerId = "player-123"

    @BeforeEach
    fun setUp() {
        controller = LobbyController(lobbyService, gameService, messagingTemplate, null)
        // Simuliert einen angemeldeten Websocket-User
        every { headerAccessor.user } returns principal
        every { principal.name } returns playerId
    }

    @Nested
    inner class JoinLobbyTests {

        @Test
        fun `joinLobby bricht ab, wenn User null ist`() {
            every { headerAccessor.user } returns null

            controller.joinLobby(PlayerMessage("ValidName"), headerAccessor)

            verify { lobbyService wasNot Called }
            verify { messagingTemplate wasNot Called }
        }

        @Test
        fun `joinLobby ohne gespeichertes Spiel macht nichts (authenticated join laeuft ueber REST)`() {
            val gameRepository = mockk<at.aau.se2.skyjo.persistence.GameRepository>()
            controller = LobbyController(lobbyService, gameService, messagingTemplate, gameRepository)
            every { gameRepository.getPlayerGame(playerId) } returns null
            every { gameRepository.getPlayerGame("Alice") } returns null

            controller.joinLobby(PlayerMessage("Alice"), headerAccessor)

            verify { lobbyService wasNot Called }
            verify { messagingTemplate wasNot Called }
        }

        @Test
        fun `joinLobby reconnectet authentifizierten Spieler ueber UserId in sein gespeichertes Spiel`() {
            val gameRepository = mockk<at.aau.se2.skyjo.persistence.GameRepository>()
            val gameState = GameUpdateMessage(
                phase = at.aau.se2.skyjo.game.model.GamePhase.AWAITING_DRAW,
                currentPlayerId = playerId,
                players = emptyList(),
                discardTopCard = null,
                drawnCard = null,
                roundResult = null,
                roundNumber = 1,
                totalScores = emptyList(),
                gameOver = false,
                gameId = "game-1",
                lobbyId = "lobby-1",
            )
            controller = LobbyController(lobbyService, gameService, messagingTemplate, gameRepository)
            every { gameRepository.getPlayerGame(playerId) } returns "game-1"
            every { gameService.reconnectPlayer(playerId, "Alice", "game-1") } returns gameState

            controller.joinLobby(PlayerMessage("Alice"), headerAccessor)

            verify { gameRepository.getPlayerGame(playerId) }
            verify { gameService.reconnectPlayer(playerId, "Alice", "game-1") }
            verify(exactly = 0) { gameService.getActiveGameId() }
            verify { messagingTemplate.convertAndSendToUser(playerId, "/queue/gamestate", gameState) }
            verify { lobbyService wasNot Called }
        }
    }

    @Nested
    inner class RestPresenceTests {
        private val user = AuthUserDto(userId = "user-1", username = "Alice")
        private val authHeader = "Bearer token"

        @Test
        fun `createLobby aktualisiert Friend Presence mit neuer Lobby`() {
            val lobby = LobbyState(
                lobbyId = "lobby-1",
                joinCode = "ABC123",
                players = listOf(LobbyPlayer(sessionId = "user-1", nickname = "Alice", isHost = true, userId = "user-1")),
            )
            controller = LobbyController(lobbyService, gameService, messagingTemplate, null, authSupport)
            every { authSupport.requireUser(authHeader) } returns user
            every { authSupport.markUserConnected("user-1", "lobby-1") } just Runs
            every { lobbyService.createLobby(user) } returns lobby

            controller.createLobby(authHeader)

            verify { authSupport.markUserConnected("user-1", "lobby-1") }
        }

        @Test
        fun `joinLobbyByCode aktualisiert Friend Presence mit beigetretener Lobby`() {
            val lobby = LobbyState(
                lobbyId = "lobby-1",
                joinCode = "ABC123",
                players = listOf(LobbyPlayer(sessionId = "user-1", nickname = "Alice", isHost = false, userId = "user-1")),
            )
            controller = LobbyController(lobbyService, gameService, messagingTemplate, null, authSupport)
            every { authSupport.requireUser(authHeader) } returns user
            every { authSupport.markUserConnected("user-1", "lobby-1") } just Runs
            every { lobbyService.joinLobby(user, "ABC123") } returns lobby

            controller.joinLobbyByCode("ABC123", authHeader)

            verify { authSupport.markUserConnected("user-1", "lobby-1") }
        }

        @Test
        fun `leaveLobbyById entfernt Lobby aus Friend Presence`() {
            val lobby = LobbyState(
                lobbyId = "lobby-1",
                joinCode = "ABC123",
                players = emptyList(),
            )
            controller = LobbyController(lobbyService, gameService, messagingTemplate, null, authSupport)
            every { authSupport.requireUser(authHeader) } returns user
            every { authSupport.markUserConnected("user-1", null) } just Runs
            every { lobbyService.leaveLobby("user-1", "lobby-1") } returns lobby

            controller.leaveLobbyById("lobby-1", authHeader)

            verify { authSupport.markUserConnected("user-1", null) }
        }
    }

    @Nested
    inner class StartGameTests {

        private fun hostLobby() = mockk<LobbyState> {
            every { lobbyId } returns "lobby-1"
            every { joinCode } returns "ABC123"
            every { players } returns listOf(
                mockk {
                    every { sessionId } returns playerId
                    every { userId } returns playerId
                    every { nickname } returns "Alice"
                    every { isHost } returns true
                },
            )
            every { status } returns LobbyStatus.IN_GAME
            every { maxPlayers } returns 4
        }

        @Test
        fun `startGame bricht ab, wenn User null ist`() {
            every { headerAccessor.user } returns null

            controller.startGame(StartGameMessage(maxRounds = 3, targetScore = 100), headerAccessor)

            verify { lobbyService wasNot Called }
            verify { gameService wasNot Called }
        }

        @Test
        fun `startGame sendet Fehler, wenn Spieler in keiner Lobby ist`() {
            every { lobbyService.getCurrentLobbyForUser(playerId) } returns null

            controller.startGame(StartGameMessage(3, 100), headerAccessor)

            verify {
                messagingTemplate.convertAndSendToUser(
                    playerId,
                    "/queue/errors",
                    mapOf("message" to "you are not in a lobby"),
                )
            }
            verify { gameService wasNot Called }
        }

        @Test
        fun `startGame sendet Fehler, wenn gameService throws`() {
            val lobbyState = hostLobby()
            every { lobbyService.getCurrentLobbyForUser(playerId) } returns lobbyState
            every { lobbyService.startGame(userId = playerId, lobbyId = "lobby-1") } returns lobbyState

            val errorMessage = "Game initialization failed"
            every { gameService.startGame("lobby-1", any<List<LobbyPlayer>>(), any<GameConfig>()) } throws
                RuntimeException(errorMessage)

            controller.startGame(StartGameMessage(3, 100), headerAccessor)

            verify {
                messagingTemplate.convertAndSendToUser(
                    playerId,
                    "/queue/errors",
                    mapOf("message" to errorMessage),
                )
            }
        }

        @Test
        fun `startGame startet Spiel mit Default Config wenn Message null ist`() {
            val lobbyState = hostLobby()
            val gameUpdateMessage = mockk<GameUpdateMessage> {
                every { gameId } returns "game-1"
                every { lobbyId } returns "lobby-1"
            }
            every { lobbyService.getCurrentLobbyForUser(playerId) } returns lobbyState
            every { lobbyService.startGame(userId = playerId, lobbyId = "lobby-1") } returns lobbyState

            val configSlot = slot<GameConfig>()
            every { gameService.startGame(eq("lobby-1"), any(), capture(configSlot)) } returns gameUpdateMessage

            controller.startGame(null, headerAccessor)

            assert(configSlot.isCaptured)
            assert(configSlot.captured.maxRounds == 3)
            assert(configSlot.captured.targetScore == 100)
            verify { messagingTemplate.convertAndSend("/topic/games/game-1", gameUpdateMessage) }
        }

        @Test
        fun `startGame startet Spiel mit Custom Config wenn Message vorhanden ist`() {
            val lobbyState = hostLobby()
            val gameUpdateMessage = mockk<GameUpdateMessage> {
                every { gameId } returns "game-1"
                every { lobbyId } returns "lobby-1"
            }
            every { lobbyService.getCurrentLobbyForUser(playerId) } returns lobbyState
            every { lobbyService.startGame(userId = playerId, lobbyId = "lobby-1") } returns lobbyState

            val configSlot = slot<GameConfig>()
            every { gameService.startGame(eq("lobby-1"), any(), capture(configSlot)) } returns gameUpdateMessage

            controller.startGame(StartGameMessage(maxRounds = 7, targetScore = 50), headerAccessor)

            assert(configSlot.captured.maxRounds == 7)
            assert(configSlot.captured.targetScore == 50)
            verify { messagingTemplate.convertAndSend("/topic/games/game-1", gameUpdateMessage) }
        }

        @Test
        fun `startGame startet aktuelles Join-Code Lobby-Spiel auf Game Topic`() {
            val playersList = listOf<LobbyPlayer>(
                mockk {
                    every { sessionId } returns playerId
                    every { userId } returns playerId
                    every { nickname } returns "Alice"
                    every { isHost } returns true
                },
            )
            val lobbyState = mockk<LobbyState> {
                every { lobbyId } returns "lobby-1"
                every { joinCode } returns "ABC123"
                every { players } returns playersList
                every { status } returns LobbyStatus.IN_GAME
                every { maxPlayers } returns 4
            }
            val gameUpdateMessage = GameUpdateMessage(
                phase = at.aau.se2.skyjo.game.model.GamePhase.AWAITING_DRAW,
                currentPlayerId = playerId,
                players = emptyList(),
                discardTopCard = null,
                drawnCard = null,
                roundResult = null,
                roundNumber = 1,
                totalScores = emptyList(),
                gameOver = false,
                gameId = "game-1",
                lobbyId = "lobby-1",
            )
            every { lobbyService.getCurrentLobbyForUser(playerId) } returns lobbyState
            every { lobbyService.startGame(userId = playerId, lobbyId = "lobby-1") } returns lobbyState
            every { gameService.startGame("lobby-1", playersList, any()) } returns gameUpdateMessage

            controller.startGame(StartGameMessage(3, 100), headerAccessor)

            verify { messagingTemplate.convertAndSend("/topic/lobbies/ABC123", any<LobbyUpdateMessage>()) }
            verify { messagingTemplate.convertAndSend("/topic/games/game-1", gameUpdateMessage) }
            verify { messagingTemplate.convertAndSendToUser(playerId, "/queue/gamestate", gameUpdateMessage) }
        }
    }
}
