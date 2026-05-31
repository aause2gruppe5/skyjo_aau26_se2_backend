package at.aau.se2.skyjo.service

import at.aau.se2.skyjo.persistence.AuthRepository
import at.aau.se2.skyjo.persistence.StatsRepository
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.datasource.SingleConnectionDataSource

class StatsServiceTest {

    private lateinit var authRepository: AuthRepository
    private lateinit var repository: StatsRepository
    private lateinit var service: StatsService

    @BeforeEach
    fun setUp() {
        val dataSource = SingleConnectionDataSource("jdbc:sqlite::memory:", true)
        val jdbc = JdbcTemplate(dataSource)
        authRepository = AuthRepository(jdbc)
        repository = StatsRepository(jdbc)
        authRepository.initSchema()
        repository.initSchema()
        createUser("user-a", "Alice")
        createUser("user-b", "Bob")
        service = StatsService(repository, authRepository, nowProvider = { 1_000L })
    }

    @Test
    fun `recordGameResult marks lowest score as winner`() {
        service.recordGameResult("game-1", mapOf("user-a" to 42, "user-b" to 10))

        assertEquals(0, service.getStats("user-a").wins)
        assertEquals(1, service.getStats("user-b").wins)
    }

    @Test
    fun `recordGameResult treats tied lowest scores as multiple winners`() {
        service.recordGameResult("game-1", mapOf("user-a" to 10, "user-b" to 10))

        assertEquals(1, service.getStats("user-a").wins)
        assertEquals(1, service.getStats("user-b").wins)
    }

    @Test
    fun `recordGameResult ignores empty score maps`() {
        service.recordGameResult("game-empty", emptyMap())

        assertEquals(0, service.getStats("user-a").gamesPlayed)
    }

    @Test
    fun `getStats returns zero state for user without games`() {
        val stats = service.getStats("user-a")

        assertEquals(0, stats.gamesPlayed)
        assertEquals(0.0, stats.averageScore)
    }

    @Test
    fun `getStats rejects unknown users`() {
        assertThrows<UnauthorizedException> {
            service.getStats("missing")
        }
    }

    @Test
    fun `leaderboard ranks players and clamps invalid limits`() {
        service.recordGameResult("game-1", mapOf("user-a" to 7, "user-b" to 15))

        val leaderboard = service.leaderboard(0)

        assertEquals(1, leaderboard.size)
        assertEquals("Alice", leaderboard.single().username)
        assertEquals(1, leaderboard.single().rank)
    }

    private fun createUser(userId: String, username: String) {
        authRepository.createUser(userId, username, "hash-$userId", now = 1L)
    }
}
