package at.aau.se2.skyjo.controller
import io.mockk.*
import io.mockk.impl.annotations.InjectMockKs
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

    @InjectMockKs
    lateinit var controller: LobbyController

    @MockK
    lateinit var headerAccessor: SimpMessageHeaderAccessor

    @MockK
    lateinit var principal: Principal

    private val playerId = "player-123"

    @BeforeEach
    fun setUp() {
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
        fun `joinLobby sendet Fehler, wenn Name nach Trim leer ist`() {
            controller.joinLobby(PlayerMessage("   "), headerAccessor)

            verify {
                messagingTemplate.convertAndSendToUser(
                    playerId,
                    "/queue/errors",
                    mapOf("message" to "Name has to be between 1 and 15 characters.")
                )
            }
            verify { lobbyService wasNot Called }
        }

        @Test
        fun `joinLobby sendet Fehler, wenn Name zu lang ist`() {
            controller.joinLobby(PlayerMessage("ThisNameIsWayTooLong1234"), headerAccessor)

            verify {
                messagingTemplate.convertAndSendToUser(
                    playerId,
                    "/queue/errors",
                    mapOf("message" to "Name has to be between 1 and 15 characters.")
                )
            }
            verify { lobbyService wasNot Called }
        }

        @Test
        fun `joinLobby sendet Fehler, wenn Nickname bereits existiert (Case Insensitive)`() {
            val currentState = mockk<LobbyState>()
            val existingPlayer = mockk<LobbyPlayer> {
                every { nickname } returns "ExistingName"
            }
            every { currentState.players } returns listOf(existingPlayer)
            every { lobbyService.getState() } returns currentState

            // Versuch mit gleichem Namen beizutreten (kleingeschrieben)
            controller.joinLobby(PlayerMessage("existingname"), headerAccessor)

            verify {
                messagingTemplate.convertAndSendToUser(
                    playerId,
                    "/queue/errors",
                    mapOf("message" to "Nickname 'existingname' is already in use")
                )
            }
            verify(exactly = 0) { lobbyService.join(any(), any()) }
        }

        @Test
        fun `joinLobby sendet Fehler, wenn lobbyService einen Fehler wirft`() {
            val currentState = mockk<LobbyState> {
                every { players } returns emptyList()
            }
            every { lobbyService.getState() } returns currentState

            val errorMessage = "cannot join: lobby is full (max 4 players)"
            every { lobbyService.join(any(), any()) } throws IllegalStateException(errorMessage)

            controller.joinLobby(PlayerMessage("ValidName"), headerAccessor)

            verify {
                messagingTemplate.convertAndSendToUser(
                    playerId,
                    "/queue/errors",
                    mapOf("message" to errorMessage)
                )
            }
        }

        @Test
        fun `joinLobby joint erfolgreich und schickt Update an Topic`() {
            val rawName = " ValidName  "
            val trimmedName = "ValidName"

            val currentState = mockk<LobbyState> {
                every { players } returns emptyList()
            }
            every { lobbyService.getState() } returns currentState

            val updatedState = mockk<LobbyState> {
                every { players } returns listOf(mockk {
                    every { nickname } returns trimmedName
                    every { isHost } returns false
                })
                // Da LobbyStatus ein Enum ist, mocken wir einen Wert
                every { status } returns mockk()
                every { maxPlayers } returns 4
            }
            every { lobbyService.join(playerId, trimmedName) } returns updatedState

            controller.joinLobby(PlayerMessage(rawName), headerAccessor)

            verify { lobbyService.join(playerId, trimmedName) }
            verify {
                messagingTemplate.convertAndSend(
                    "/topic/lobby",
                    any<LobbyUpdateMessage>()
                )
            }
        }
    }

    @Nested
    inner class LeaveLobbyTests {

        @Test
        fun `leaveLobby bricht ab, wenn User null ist`() {
            every { headerAccessor.user } returns null

            controller.leaveLobby(headerAccessor)

            verify { lobbyService wasNot Called }
        }

        @Test
        fun `leaveLobby verlaesst erfolgreich und schickt Update an Topic`() {
            val updatedState = mockk<LobbyState> {
                every { players } returns emptyList()
                every { status } returns mockk()
                every { maxPlayers } returns 4
            }
            every { lobbyService.leave(playerId) } returns updatedState

            controller.leaveLobby(headerAccessor)

            verify { lobbyService.leave(playerId) }
            verify {
                messagingTemplate.convertAndSend(
                    "/topic/lobby",
                    any<LobbyUpdateMessage>()
                )
            }
        }
    }

    @Nested
    inner class StartGameTests {

        @Test
        fun `startGame bricht ab, wenn User null ist`() {
            every { headerAccessor.user } returns null

            controller.startGame(StartGameMessage(maxRounds = 3, targetScore = 100), headerAccessor)

            verify { lobbyService wasNot Called }
            verify { gameService wasNot Called }
        }

        @Test
        fun `startGame sendet Fehler, wenn lobbyService throws`() {
            val errorMessage = "only the host can start the game"
            every { lobbyService.startGame(playerId) } throws IllegalStateException(errorMessage)

            controller.startGame(StartGameMessage(3, 100), headerAccessor)

            verify {
                messagingTemplate.convertAndSendToUser(
                    playerId,
                    "/queue/errors",
                    mapOf("message" to errorMessage)
                )
            }
            verify { gameService wasNot Called }
        }

        @Test
        fun `startGame sendet Fehler, wenn gameService throws`() {
            val lobbyState = mockk<LobbyState> {
                every { players } returns emptyList()
                every { status } returns mockk()
                every { maxPlayers } returns 4
            }
            every { lobbyService.startGame(playerId) } returns lobbyState

            val errorMessage = "Game initialization failed"
            every { gameService.startGame(any(), any()) } throws RuntimeException(errorMessage)

            controller.startGame(StartGameMessage(3, 100), headerAccessor)

            verify {
                messagingTemplate.convertAndSendToUser(
                    playerId,
                    "/queue/errors",
                    mapOf("message" to errorMessage)
                )
            }
        }

        @Test
        fun `startGame startet Spiel mit Default Config wenn Message null ist`() {
            val playersList = listOf<LobbyPlayer>(mockk {
                every { nickname } returns "p1"
                every { isHost } returns true
            })
            val lobbyState = mockk<LobbyState> {
                every { players } returns playersList
                every { status } returns mockk()
                every { maxPlayers } returns 4
            }
            every { lobbyService.startGame(playerId) } returns lobbyState

            // Verwendet nun dein GameUpdateMessage DTO
            val gameUpdateMessage = mockk<GameUpdateMessage>()

            val configSlot = slot<GameConfig>()
            every { gameService.startGame(eq(playersList), capture(configSlot)) } returns gameUpdateMessage

            // Message ist null
            controller.startGame(null, headerAccessor)

            assert(configSlot.isCaptured)
            // Prüft, ob deine Defaults aus der Datenklasse verwendet wurden
            assert(configSlot.captured.maxRounds == 3)
            assert(configSlot.captured.targetScore == 100)

            verify { messagingTemplate.convertAndSend("/topic/lobby", any<LobbyUpdateMessage>()) }
            verify { messagingTemplate.convertAndSend("/topic/game", gameUpdateMessage) }
        }

        @Test
        fun `startGame startet Spiel mit Custom Config wenn Message vorhanden ist`() {
            val playersList = emptyList<LobbyPlayer>()
            val lobbyState = mockk<LobbyState> {
                every { players } returns playersList
                every { status } returns mockk()
                every { maxPlayers } returns 4
            }
            every { lobbyService.startGame(playerId) } returns lobbyState

            val gameUpdateMessage = mockk<GameUpdateMessage>()
            val customMessage = StartGameMessage(maxRounds = 7, targetScore = 50)

            every {
                gameService.startGame(
                    eq(playersList),
                    match { it.maxRounds == 7 && it.targetScore == 50 }
                )
            } returns gameUpdateMessage

            controller.startGame(customMessage, headerAccessor)

            verify { messagingTemplate.convertAndSend("/topic/lobby", any<LobbyUpdateMessage>()) }
            verify { messagingTemplate.convertAndSend("/topic/game", gameUpdateMessage) }
        }
    }
}