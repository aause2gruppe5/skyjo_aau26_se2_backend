package at.aau.se2.skyjo.service

import at.aau.se2.skyjo.model.auth.AuthUserDto
import at.aau.se2.skyjo.model.social.LobbyInviteStatus
import at.aau.se2.skyjo.persistence.AuthRepository
import at.aau.se2.skyjo.persistence.FriendRepository
import at.aau.se2.skyjo.persistence.LobbyInviteRepository
import at.aau.se2.skyjo.persistence.LobbyRepository
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.datasource.SingleConnectionDataSource

class LobbyInviteServiceTest {

    private lateinit var authRepository: AuthRepository
    private lateinit var friendRepository: FriendRepository
    private lateinit var lobbyRepository: LobbyRepository
    private lateinit var inviteRepository: LobbyInviteRepository
    private lateinit var lobbyService: LobbyService
    private lateinit var service: LobbyInviteService

    @BeforeEach
    fun setUp() {
        val dataSource = SingleConnectionDataSource("jdbc:sqlite::memory:", true)
        val jdbc = JdbcTemplate(dataSource)
        authRepository = AuthRepository(jdbc)
        friendRepository = FriendRepository(jdbc)
        lobbyRepository = LobbyRepository(jdbc)
        inviteRepository = LobbyInviteRepository(jdbc)
        authRepository.initSchema()
        friendRepository.initSchema()
        lobbyRepository.initSchema()
        inviteRepository.initSchema()
        createUser("user-a", "Alice")
        createUser("user-b", "Bob")
        createUser("user-c", "Cara")
        friendRepository.createFriendship("user-a", "user-b", now = 1_000L)
        friendRepository.createFriendship("user-b", "user-a", now = 1_000L)
        lobbyService = LobbyService(
            repository = lobbyRepository,
            joinCodeGenerator = object : JoinCodeGenerator {
                override fun generateCode(): String = "ABC123"
            },
            idGenerator = object : LobbyIdGenerator {
                override fun generateId(): String = "lobby-1"
            },
            nowProvider = { 1_000L },
        )
        service = LobbyInviteService(
            repository = inviteRepository,
            authRepository = authRepository,
            friendRepository = friendRepository,
            lobbyService = lobbyService,
            inviteIdGenerator = object : LobbyInviteIdGenerator {
                override fun generateId(): String = "invite-1"
            },
            nowProvider = { 2_000L },
        )
    }

    @Test
    fun `createInvite rejects non-friends`() {
        val lobby = lobbyService.createLobby(user("user-a", "Alice"))

        val error = assertThrows<IllegalStateException> {
            service.createInvite(user("user-a", "Alice"), lobby.lobbyId!!, toUserId = "user-c")
        }

        assertTrue(error.message.orEmpty().contains("friends"))
    }

    @Test
    fun `createInvite rejects self invites`() {
        val lobby = lobbyService.createLobby(user("user-a", "Alice"))

        val error = assertThrows<IllegalStateException> {
            service.createInvite(user("user-a", "Alice"), lobby.lobbyId!!, toUserId = "user-a")
        }

        assertTrue(error.message.orEmpty().contains("yourself"))
    }

    @Test
    fun `createInvite rejects unknown target users`() {
        val lobby = lobbyService.createLobby(user("user-a", "Alice"))

        val error = assertThrows<IllegalStateException> {
            service.createInvite(user("user-a", "Alice"), lobby.lobbyId!!, toUserId = "missing")
        }

        assertTrue(error.message.orEmpty().contains("user not found"))
    }

    @Test
    fun `createInvite requires inviter to be lobby member`() {
        val lobby = lobbyService.createLobby(user("user-a", "Alice"))

        val error = assertThrows<IllegalStateException> {
            service.createInvite(user("user-b", "Bob"), lobby.lobbyId!!, toUserId = "user-a")
        }

        assertTrue(error.message.orEmpty().contains("lobby member"))
    }

    @Test
    fun `createInvite rejects non-waiting lobbies`() {
        val lobby = lobbyService.createLobby(user("user-a", "Alice"))
        lobbyService.joinLobby(user("user-b", "Bob"), lobby.joinCode!!)
        lobbyService.startGame(userId = "user-a", lobbyId = lobby.lobbyId!!)

        val error = assertThrows<IllegalStateException> {
            service.createInvite(user("user-a", "Alice"), lobby.lobbyId!!, toUserId = "user-b")
        }

        assertTrue(error.message.orEmpty().contains("not waiting"))
    }

    @Test
    fun `createInvite rejects duplicate pending invites`() {
        val lobby = lobbyService.createLobby(user("user-a", "Alice"))
        service.createInvite(user("user-a", "Alice"), lobby.lobbyId!!, toUserId = "user-b")

        val error = assertThrows<IllegalStateException> {
            service.createInvite(user("user-a", "Alice"), lobby.lobbyId!!, toUserId = "user-b")
        }

        assertTrue(error.message.orEmpty().contains("already exists"))
    }

    @Test
    fun `listInvites returns pending invites for the invited user`() {
        val lobby = lobbyService.createLobby(user("user-a", "Alice"))
        service.createInvite(user("user-a", "Alice"), lobby.lobbyId!!, toUserId = "user-b")

        val invites = service.listInvites(user("user-b", "Bob"))

        assertEquals(listOf("ABC123"), invites.map { it.joinCode })
    }

    @Test
    fun `acceptInvite joins invited user into lobby`() {
        val lobby = lobbyService.createLobby(user("user-a", "Alice"))
        val invite = service.createInvite(user("user-a", "Alice"), lobby.lobbyId!!, toUserId = "user-b")

        val accepted = service.acceptInvite(user("user-b", "Bob"), invite.inviteId)

        assertEquals(LobbyInviteStatus.ACCEPTED, accepted.status)
        assertEquals(listOf("Alice", "Bob"), lobbyService.getLobbyById(lobby.lobbyId!!)?.players?.map { it.nickname })
    }

    @Test
    fun `acceptInvite rejects users that are not invited`() {
        val lobby = lobbyService.createLobby(user("user-a", "Alice"))
        val invite = service.createInvite(user("user-a", "Alice"), lobby.lobbyId!!, toUserId = "user-b")

        assertThrows<UnauthorizedException> {
            service.acceptInvite(user("user-c", "Cara"), invite.inviteId)
        }
    }

    @Test
    fun `acceptInvite rejects invites for lobbies that are no longer waiting`() {
        val lobby = lobbyService.createLobby(user("user-a", "Alice"))
        val lobbyId = lobby.lobbyId!!
        val invite = service.createInvite(user("user-a", "Alice"), lobbyId, toUserId = "user-b")
        lobbyService.joinLobby(user("user-c", "Cara"), lobby.joinCode!!)
        lobbyService.startGame(userId = "user-a", lobbyId = lobbyId)

        val error = assertThrows<IllegalStateException> {
            service.acceptInvite(user("user-b", "Bob"), invite.inviteId)
        }

        assertTrue(error.message.orEmpty().contains("not waiting"))
    }

    @Test
    fun `declineInvite marks invite declined and hides it from pending invites`() {
        val lobby = lobbyService.createLobby(user("user-a", "Alice"))
        val invite = service.createInvite(user("user-a", "Alice"), lobby.lobbyId!!, toUserId = "user-b")

        val declined = service.declineInvite(user("user-b", "Bob"), invite.inviteId)

        assertEquals(LobbyInviteStatus.DECLINED, declined.status)
        assertTrue(service.listInvites(user("user-b", "Bob")).isEmpty())
    }

    @Test
    fun `declineInvite rejects invites that are no longer pending`() {
        val lobby = lobbyService.createLobby(user("user-a", "Alice"))
        val invite = service.createInvite(user("user-a", "Alice"), lobby.lobbyId!!, toUserId = "user-b")
        service.declineInvite(user("user-b", "Bob"), invite.inviteId)

        val error = assertThrows<IllegalStateException> {
            service.declineInvite(user("user-b", "Bob"), invite.inviteId)
        }

        assertTrue(error.message.orEmpty().contains("not pending"))
    }

    private fun createUser(userId: String, username: String) {
        authRepository.createUser(userId, username, "hash-$userId", now = 1L)
    }

    private fun user(userId: String, username: String) = AuthUserDto(userId = userId, username = username)
}
