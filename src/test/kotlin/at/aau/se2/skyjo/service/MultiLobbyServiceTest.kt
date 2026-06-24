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
    fun `new lobby is waiting and accepts join code joins`() {
        val lobby = service.createLobby(user("user-1", "Alice"))

        val joined = service.joinLobby(user("user-2", "Bob"), lobby.joinCode!!)

        assertEquals(LobbyStatus.WAITING, joined.status)
        assertEquals(listOf("Alice", "Bob"), joined.players.map { it.nickname })
    }

    @Test
    fun `failed one-player start leaves lobby waiting and joinable`() {
        val lobby = service.createLobby(user("user-1", "Alice"))

        assertThrows<IllegalStateException> {
            service.startGame(userId = "user-1", lobbyId = lobby.lobbyId!!)
        }
        val joined = service.joinLobby(user("user-2", "Bob"), lobby.joinCode!!)

        assertEquals(LobbyStatus.WAITING, joined.status)
        assertEquals(listOf("Alice", "Bob"), joined.players.map { it.nickname })
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
    fun `createLobby rejects user already in waiting lobby`() {
        service.createLobby(user("user-1", "Alice"))

        val error = assertThrows<IllegalStateException> {
            service.createLobby(user("user-1", "Alice"))
        }

        assertTrue(error.message.orEmpty().contains("already in a lobby"))
    }

    @Test
    fun `joinLobby is idempotent for users already in that lobby`() {
        val lobby = service.createLobby(user("user-1", "Alice"))

        val joined = service.joinLobby(user("user-1", "Alice"), lobby.joinCode!!)

        assertEquals(listOf("Alice"), joined.players.map { it.nickname })
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
    fun `last player leaving closes the lobby`() {
        val lobby = service.createLobby(user("user-1", "Alice"))

        val updated = service.leaveLobby(userId = "user-1", lobbyId = lobby.lobbyId!!)

        assertEquals(LobbyStatus.CLOSED, updated.status)
        assertTrue(updated.players.isEmpty())
    }

    @Test
    fun `non-member leaving keeps lobby members unchanged`() {
        val lobby = service.createLobby(user("user-1", "Alice"))
        service.joinLobby(user("user-2", "Bob"), lobby.joinCode!!)

        val updated = service.leaveLobby(userId = "missing", lobbyId = lobby.lobbyId!!)

        assertEquals(listOf("Alice", "Bob"), updated.players.map { it.nickname })
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

    @Test
    fun `startGame rejects lobbies that are already in game`() {
        val lobby = service.createLobby(user("user-1", "Alice"))
        val lobbyId = lobby.lobbyId!!
        service.joinLobby(user("user-2", "Bob"), lobby.joinCode!!)
        service.startGame(userId = "user-1", lobbyId = lobbyId)

        val error = assertThrows<IllegalStateException> {
            service.startGame(userId = "user-1", lobbyId = lobbyId)
        }

        assertTrue(error.message.orEmpty().contains("not waiting"))
    }

    @Test
    fun `joinLobby rejects lobbies after game start`() {
        val lobby = service.createLobby(user("user-1", "Alice"))
        service.joinLobby(user("user-2", "Bob"), lobby.joinCode!!)
        service.startGame(userId = "user-1", lobbyId = lobby.lobbyId!!)

        val error = assertThrows<IllegalStateException> {
            service.joinLobby(user("user-3", "Cara"), lobby.joinCode!!)
        }

        assertTrue(error.message.orEmpty().contains("in progress"))
    }

    @Test
    fun `joinLobby lets existing members rejoin an in game lobby by code`() {
        val lobby = service.createLobby(user("user-1", "Alice"))
        service.joinLobby(user("user-2", "Bob"), lobby.joinCode!!)
        service.startGame(userId = "user-1", lobbyId = lobby.lobbyId!!)

        val rejoined = service.joinLobby(user("user-2", "Bob"), lobby.joinCode!!)

        assertEquals(LobbyStatus.IN_GAME, rejoined.status)
        assertEquals(lobby.lobbyId, rejoined.lobbyId)
        assertEquals(listOf("Alice", "Bob"), rejoined.players.map { it.nickname })
    }

    @Test
    fun `joinLobby rejects closed lobbies`() {
        val lobby = service.createLobby(user("user-1", "Alice"))
        service.closeLobby(lobby.lobbyId!!)

        val error = assertThrows<IllegalStateException> {
            service.joinLobby(user("user-2", "Bob"), lobby.joinCode!!)
        }

        assertTrue(error.message.orEmpty().contains("closed"))
    }

    @Test
    fun `startGame rejects non-host users`() {
        val lobby = service.createLobby(user("user-1", "Alice"))
        service.joinLobby(user("user-2", "Bob"), lobby.joinCode!!)

        val error = assertThrows<IllegalStateException> {
            service.startGame(userId = "user-2", lobbyId = lobby.lobbyId!!)
        }

        assertTrue(error.message.orEmpty().contains("host"))
    }

    @Test
    fun `startGame rejects lobbies with one player`() {
        val lobby = service.createLobby(user("user-1", "Alice"))

        val error = assertThrows<IllegalStateException> {
            service.startGame(userId = "user-1", lobbyId = lobby.lobbyId!!)
        }

        assertTrue(error.message.orEmpty().contains("2 players"))
    }

    @Test
    fun `joinLobby rejects full lobbies`() {
        val lobby = service.createLobby(user("user-1", "Alice"))
        val joinCode = lobby.joinCode!!
        service.joinLobby(user("user-2", "Bob"), joinCode)
        service.joinLobby(user("user-3", "Cara"), joinCode)
        service.joinLobby(user("user-4", "Dan"), joinCode)
        service.joinLobby(user("user-5", "Eve"), joinCode)
        service.joinLobby(user("user-6", "Finn"), joinCode)

        val error = assertThrows<IllegalStateException> {
            service.joinLobby(user("user-7", "Gina"), joinCode)
        }

        assertTrue(error.message.orEmpty().contains("full"))
    }

    private fun user(id: String, username: String) = AuthUserDto(userId = id, username = username)
}
