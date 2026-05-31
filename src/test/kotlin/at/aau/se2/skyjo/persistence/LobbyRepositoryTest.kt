package at.aau.se2.skyjo.persistence

import at.aau.se2.skyjo.model.lobby.LobbyStatus
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.datasource.SingleConnectionDataSource

class LobbyRepositoryTest {

    private lateinit var repo: LobbyRepository

    @BeforeEach
    fun setUp() {
        val dataSource = SingleConnectionDataSource("jdbc:sqlite::memory:", true)
        val jdbc = JdbcTemplate(dataSource)
        repo = LobbyRepository(jdbc)
        repo.initSchema()
    }

    @Test
    fun `creates and loads lobby by join code`() {
        repo.createLobby("lobby-1", "ABC123", "user-1", maxPlayers = 6, now = 1_000L)

        val lobby = repo.findLobbyByJoinCode("abc123")

        assertEquals("lobby-1", lobby?.lobbyId)
        assertEquals("ABC123", lobby?.joinCode)
        assertEquals("user-1", lobby?.hostUserId)
        assertEquals(LobbyStatus.WAITING, lobby?.status)
    }

    @Test
    fun `stores lobby members and finds current lobby for user`() {
        repo.createLobby("lobby-1", "ABC123", "user-1", maxPlayers = 6, now = 1_000L)
        repo.upsertMember("lobby-1", "user-1", "Alice", isHost = true, joinedAt = 1_000L)
        repo.upsertMember("lobby-1", "user-2", "Bob", isHost = false, joinedAt = 2_000L)

        val members = repo.listMembers("lobby-1")
        val currentLobby = repo.findCurrentLobbyForUser("user-2")

        assertEquals(listOf("Alice", "Bob"), members.map { it.username })
        assertEquals("lobby-1", currentLobby?.lobbyId)
    }

    @Test
    fun `removing member and closing lobby updates persisted state`() {
        repo.createLobby("lobby-1", "ABC123", "user-1", maxPlayers = 6, now = 1_000L)
        repo.upsertMember("lobby-1", "user-1", "Alice", isHost = true, joinedAt = 1_000L)

        repo.removeMember("lobby-1", "user-1")
        repo.updateLobbyStatus("lobby-1", LobbyStatus.CLOSED, now = 2_000L)

        assertTrue(repo.listMembers("lobby-1").isEmpty())
        assertNull(repo.findCurrentLobbyForUser("user-1"))
        assertEquals(LobbyStatus.CLOSED, repo.findLobbyById("lobby-1")?.status)
    }
}
