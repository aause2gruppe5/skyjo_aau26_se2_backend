package at.aau.se2.skyjo.persistence

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.datasource.SingleConnectionDataSource

class AuthRepositoryTest {

    private lateinit var repo: AuthRepository

    @BeforeEach
    fun setUp() {
        val dataSource = SingleConnectionDataSource("jdbc:sqlite::memory:", true)
        val jdbc = JdbcTemplate(dataSource)
        repo = AuthRepository(jdbc)
        repo.initSchema()
    }

    @Test
    fun `usernames are unique case-insensitively`() {
        repo.createUser(
            userId = "user-1",
            username = "Alice",
            passwordHash = "bcrypt-hash",
            now = 1_000L,
        )

        val duplicateError = assertThrows<Exception> {
            repo.createUser(
                userId = "user-2",
                username = "alice",
                passwordHash = "other-hash",
                now = 2_000L,
            )
        }

        assertTrue(duplicateError.message.orEmpty().contains("UNIQUE", ignoreCase = true))
        val user = repo.findUserByUsername("ALICE")
        assertEquals("user-1", user?.userId)
        assertEquals("Alice", user?.username)
        assertEquals("bcrypt-hash", user?.passwordHash)
    }

    @Test
    fun `expired sessions are not returned`() {
        repo.createUser("user-1", "Alice", "hash", now = 1_000L)
        repo.createSession(
            tokenHash = "token-hash",
            userId = "user-1",
            createdAt = 1_000L,
            expiresAt = 2_000L,
        )

        assertNull(repo.findActiveSession("token-hash", now = 2_001L))
    }

    @Test
    fun `revoked sessions are not returned`() {
        repo.createUser("user-1", "Alice", "hash", now = 1_000L)
        repo.createSession("token-hash", "user-1", createdAt = 1_000L, expiresAt = 10_000L)

        repo.revokeSession("token-hash", now = 2_000L)

        assertNull(repo.findActiveSession("token-hash", now = 2_001L))
    }

    @Test
    fun `active session can be touched and resolved`() {
        repo.createUser("user-1", "Alice", "hash", now = 1_000L)
        repo.createSession("token-hash", "user-1", createdAt = 1_000L, expiresAt = 10_000L)

        repo.touchSession("token-hash", now = 3_000L)

        val session = repo.findActiveSession("token-hash", now = 3_001L)
        assertEquals("token-hash", session?.tokenHash)
        assertEquals("user-1", session?.userId)
        assertEquals(3_000L, session?.lastSeen)
    }

    @Test
    fun `websocket ticket can be consumed once only`() {
        repo.createUser("user-1", "Alice", "hash", now = 1_000L)
        repo.createWebSocketTicket(
            ticketHash = "ticket-hash",
            userId = "user-1",
            createdAt = 1_000L,
            expiresAt = 2_000L,
        )

        val consumed = repo.consumeWebSocketTicket("ticket-hash", now = 1_500L)
        val consumedAgain = repo.consumeWebSocketTicket("ticket-hash", now = 1_501L)

        assertEquals("user-1", consumed?.userId)
        assertNull(consumedAgain)
    }

    @Test
    fun `expired websocket ticket cannot be consumed`() {
        repo.createUser("user-1", "Alice", "hash", now = 1_000L)
        repo.createWebSocketTicket("ticket-hash", "user-1", createdAt = 1_000L, expiresAt = 2_000L)

        assertNull(repo.consumeWebSocketTicket("ticket-hash", now = 2_001L))
    }

    @Test
    fun `presence can be stored and cleared`() {
        repo.createUser("user-1", "Alice", "hash", now = 1_000L)

        repo.setPresence(userId = "user-1", connected = true, currentLobbyId = "lobby-1", now = 2_000L)
        val online = repo.getPresence("user-1")
        repo.setPresence(userId = "user-1", connected = false, currentLobbyId = null, now = 3_000L)
        val offline = repo.getPresence("user-1")

        assertTrue(online?.connected == true)
        assertEquals("lobby-1", online?.currentLobbyId)
        assertEquals(2_000L, online?.lastSeenAt)
        assertFalse(offline?.connected == true)
        assertNull(offline?.currentLobbyId)
        assertEquals(3_000L, offline?.lastSeenAt)
    }

    @Test
    fun `initSchema migrates legacy presence table with last seen column`() {
        val dataSource = SingleConnectionDataSource("jdbc:sqlite::memory:", true)
        val jdbc = JdbcTemplate(dataSource)
        jdbc.execute(
            """
            CREATE TABLE user_presence (
                user_id TEXT PRIMARY KEY,
                connected INTEGER NOT NULL DEFAULT 0,
                current_lobby_id TEXT,
                updated_at INTEGER NOT NULL
            )
            """.trimIndent(),
        )
        jdbc.update(
            """
            INSERT INTO user_presence (user_id, connected, current_lobby_id, updated_at)
            VALUES (?, 1, ?, ?)
            """.trimIndent(),
            "user-legacy",
            "lobby-1",
            2_000L,
        )

        val legacyRepo = AuthRepository(jdbc)
        legacyRepo.initSchema()

        val presence = legacyRepo.getPresence("user-legacy")
        assertTrue(presence?.connected == true)
        assertEquals("lobby-1", presence?.currentLobbyId)
        assertEquals(2_000L, presence?.updatedAt)
        assertEquals(0L, presence?.lastSeenAt)
    }

    @Test
    fun `current lobby can be cleared without changing presence state`() {
        repo.createUser("user-1", "Alice", "hash", now = 1_000L)
        repo.createUser("user-2", "Bob", "hash", now = 1_000L)
        repo.setPresence(userId = "user-1", connected = true, currentLobbyId = "lobby-1", now = 2_000L)
        repo.setPresence(userId = "user-2", connected = true, currentLobbyId = "lobby-2", now = 2_000L)

        repo.clearCurrentLobby("lobby-1", now = 3_000L)

        val cleared = repo.getPresence("user-1")
        val unchanged = repo.getPresence("user-2")
        assertTrue(cleared?.connected == true)
        assertNull(cleared?.currentLobbyId)
        assertEquals(3_000L, cleared?.updatedAt)
        assertEquals(2_000L, cleared?.lastSeenAt)
        assertEquals("lobby-2", unchanged?.currentLobbyId)
        assertEquals(2_000L, unchanged?.updatedAt)
        assertEquals(2_000L, unchanged?.lastSeenAt)
    }
}
