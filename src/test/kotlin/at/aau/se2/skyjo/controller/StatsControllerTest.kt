package at.aau.se2.skyjo.controller

import at.aau.se2.skyjo.model.auth.AuthUserDto
import at.aau.se2.skyjo.model.stats.LeaderboardEntryDto
import at.aau.se2.skyjo.model.stats.PlayerStatsDto
import at.aau.se2.skyjo.service.AuthService
import at.aau.se2.skyjo.service.StatsService
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import org.springframework.http.HttpStatus

class StatsControllerTest {

    private val authService: AuthService = mock()
    private val statsService: StatsService = mock()
    private val controller = StatsController(statsService, AuthSupport(authService))

    @Test
    fun `myStats returns authenticated user stats`() {
        whenever(authService.requireUser("token")).thenReturn(user())
        whenever(statsService.getStats("user-a")).thenReturn(stats())

        val result = controller.myStats("Bearer token")

        assertEquals(HttpStatus.OK, result.statusCode)
        assertEquals("Alice", (result.body as PlayerStatsDto).username)
    }

    @Test
    fun `leaderboard returns ranked entries`() {
        whenever(statsService.leaderboard(50)).thenReturn(
            listOf(LeaderboardEntryDto(rank = 1, userId = "user-a", username = "Alice", averageScore = 10.0, wins = 1, gamesPlayed = 2, bestScore = 8, totalScore = 20)),
        )

        val result = controller.leaderboard(limit = null)

        assertEquals(HttpStatus.OK, result.statusCode)
        assertEquals(1, (result.body as List<*>).filterIsInstance<LeaderboardEntryDto>().single().rank)
    }

    private fun user() = AuthUserDto("user-a", "Alice")

    private fun stats() = PlayerStatsDto(
        userId = "user-a",
        username = "Alice",
        gamesPlayed = 0,
        wins = 0,
        totalScore = 0,
        bestScore = null,
        averageScore = 0.0,
    )
}
