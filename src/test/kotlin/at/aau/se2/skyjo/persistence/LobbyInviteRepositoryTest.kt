package at.aau.se2.skyjo.persistence

import at.aau.se2.skyjo.model.social.LobbyInviteStatus
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.datasource.SingleConnectionDataSource

class LobbyInviteRepositoryTest {

    private lateinit var authRepository: AuthRepository
    private lateinit var lobbyRepository: LobbyRepository
    private lateinit var repository: LobbyInviteRepository

    @BeforeEach
    fun setUp() {
        val dataSource = SingleConnectionDataSource("jdbc:sqlite::memory:", true)
        val jdbc = JdbcTemplate(dataSource)
        authRepository = AuthRepository(jdbc)
        lobbyRepository = LobbyRepository(jdbc)
        repository = LobbyInviteRepository(jdbc)
        authRepository.initSchema()
        lobbyRepository.initSchema()
        repository.initSchema()
        authRepository.createUser("user-a", "Alice", "hash-a", now = 1L)
        authRepository.createUser("user-b", "Bob", "hash-b", now = 1L)
        lobbyRepository.createLobby("lobby-1", "ABC123", "user-a", maxPlayers = 6, now = 1_000L)
    }

    @Test
    fun `createInvite can be listed by recipient`() {
        repository.createInvite(
            inviteId = "invite-1",
            lobbyId = "lobby-1",
            joinCode = "ABC123",
            fromUserId = "user-a",
            toUserId = "user-b",
            now = 1_000L,
        )

        val invites = repository.listPendingInvitesForUser("user-b")

        assertEquals("invite-1", invites.single().inviteId)
        assertEquals("ABC123", invites.single().joinCode)
        assertEquals(LobbyInviteStatus.PENDING, invites.single().status)
    }

    @Test
    fun `updateInviteStatus stores response timestamp`() {
        repository.createInvite("invite-1", "lobby-1", "ABC123", "user-a", "user-b", now = 1_000L)

        val updated = repository.updateInviteStatus("invite-1", LobbyInviteStatus.ACCEPTED, respondedAt = 2_000L)

        assertEquals(LobbyInviteStatus.ACCEPTED, updated!!.status)
        assertEquals(2_000L, updated.respondedAt)
    }
}
