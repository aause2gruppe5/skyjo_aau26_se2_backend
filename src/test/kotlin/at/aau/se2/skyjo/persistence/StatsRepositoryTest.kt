package at.aau.se2.skyjo.persistence

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.datasource.SingleConnectionDataSource

class StatsRepositoryTest {

    private lateinit var authRepository: AuthRepository
    private lateinit var repository: StatsRepository

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
        createUser("user-c", "Cara")
    }

    @Test
    fun `recordResult inserts and updates player stats`() {
        repository.recordResult("user-a", totalScore = 20, won = true, now = 1_000L)
        repository.recordResult("user-a", totalScore = 30, won = false, now = 2_000L)

        val stats = repository.findStats("user-a")!!

        assertEquals(2, stats.gamesPlayed)
        assertEquals(1, stats.wins)
        assertEquals(50, stats.totalScore)
        assertEquals(20, stats.bestScore)
    }

    @Test
    fun `leaderboard sorts by lowest average then wins then games`() {
        repository.recordResult("user-a", totalScore = 20, won = false, now = 1_000L)
        repository.recordResult("user-b", totalScore = 20, won = true, now = 1_000L)
        repository.recordResult("user-c", totalScore = 10, won = false, now = 1_000L)
        repository.recordResult("user-c", totalScore = 30, won = false, now = 1_000L)

        val leaderboard = repository.leaderboard(limit = 10)

        assertEquals(listOf("Bob", "Cara", "Alice"), leaderboard.map { it.username })
    }

    @Test
    fun `findStats returns null for users without games`() {
        assertNull(repository.findStats("user-a"))
    }

    private fun createUser(userId: String, username: String) {
        authRepository.createUser(userId, username, "hash-$userId", now = 1L)
    }
}
