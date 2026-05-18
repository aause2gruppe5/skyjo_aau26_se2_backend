package at.aau.se2.skyjo.service

import at.aau.se2.skyjo.model.auth.AuthUserDto
import at.aau.se2.skyjo.model.lobby.LobbyStatus
import at.aau.se2.skyjo.persistence.LobbyRepository
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.datasource.SingleConnectionDataSource

class MultiLobbyServiceTest {

    private lateinit var service: LobbyService
    private val codes = ArrayDeque(listOf("AAAAAA", "BBBBBB", "CCCCCC"))

    @BeforeEach
    fun setUp() {
        val dataSource = SingleConnectionDataSource("jdbc:sqlite::memory:", true)
        val repo = LobbyRepository(JdbcTemplate(dataSource))
        repo.initSchema()
        service = LobbyService(
            repository = repo,
            joinCodeGenerator = object : JoinCodeGenerator {
                override fun generateCode(): String = codes.removeFirst()
            },
            idGenerator = object : LobbyIdGenerator {
                private var next = 0
                override fun generateId(): String {
                    next += 1
                    return "lobby-$next"
                }
            },
            nowProvider = { 1_000L },
        )
    }

    @Test
    fun `users can create separate lobbies with distinct join codes`() {
        val first = service.createLobby(user("user-1", "Alice"))
        val second = service.createLobby(user("user-2", "Bob"))

        assertNotEquals(first.lobbyId, second.lobbyId)
        assertEquals("AAAAAA", first.joinCode)
        assertEquals("BBBBBB", second.joinCode)
        assertEquals("Alice", first.players.single().nickname)
        assertEquals("Bob", second.players.single().nickname)
    }

    @Test
    fun `joinLobby joins only the lobby matching the join code`() {
        val first = service.createLobby(user("user-1", "Alice"))
        val second = service.createLobby(user("user-2", "Bob"))

        val joined = service.joinLobby(user("user-3", "Cara"), second.joinCode!!)

        assertEquals(first.lobbyId, service.getLobbyById(first.lobbyId!!)?.lobbyId)
        assertEquals(listOf("Bob", "Cara"), joined.players.map { it.nickname })
    }

    @Test
    fun `user cannot join two waiting lobbies`() {
        service.createLobby(user("user-1", "Alice"))
        service.createLobby(user("user-2", "Bob"))

        val error = assertThrows<IllegalStateException> {
            service.joinLobby(user("user-1", "Alice"), "BBBBBB")
        }

        assertTrue(error.message.orEmpty().contains("already in a lobby"))
    }

    @Test
    fun `host leaving reassigns host in that lobby`() {
        val lobby = service.createLobby(user("user-1", "Alice"))
        service.joinLobby(user("user-2", "Bob"), lobby.joinCode!!)

        val updated = service.leaveLobby(userId = "user-1", lobbyId = lobby.lobbyId!!)

        assertEquals(listOf("Bob"), updated.players.map { it.nickname })
        assertTrue(updated.players.single().isHost)
    }

    @Test
    fun `startGame marks only selected lobby in game`() {
        val first = service.createLobby(user("user-1", "Alice"))
        service.joinLobby(user("user-2", "Bob"), first.joinCode!!)
        val second = service.createLobby(user("user-3", "Cara"))

        val started = service.startGame(userId = "user-1", lobbyId = first.lobbyId!!)

        assertEquals(LobbyStatus.IN_GAME, started.status)
        assertEquals(LobbyStatus.WAITING, service.getLobbyById(second.lobbyId!!)?.status)
    }

    private fun user(id: String, username: String) = AuthUserDto(userId = id, username = username)
}
