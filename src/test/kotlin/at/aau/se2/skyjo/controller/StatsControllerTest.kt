package at.aau.se2.skyjo.controller

import at.aau.se2.skyjo.model.auth.AuthUserDto
import at.aau.se2.skyjo.model.auth.ErrorResponse
import at.aau.se2.skyjo.model.stats.LeaderboardEntryDto
import at.aau.se2.skyjo.model.stats.PlayerStatsDto
import at.aau.se2.skyjo.service.AuthService
import at.aau.se2.skyjo.service.StatsService
import at.aau.se2.skyjo.service.UnauthorizedException
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
    fun `myStats returns unauthorized when token is invalid`() {
        whenever(authService.requireUser("bad")).thenThrow(UnauthorizedException())

        val result = controller.myStats("Bearer bad")

        assertEquals(HttpStatus.UNAUTHORIZED, result.statusCode)
        assertEquals("Authentication required", (result.body as ErrorResponse).message)
    }

    @Test
    fun `myStats maps service errors to bad request`() {
        whenever(authService.requireUser("token")).thenReturn(user())
        whenever(statsService.getStats("user-a")).thenThrow(IllegalStateException("stats unavailable"))

        val result = controller.myStats("Bearer token")

        assertEquals(HttpStatus.BAD_REQUEST, result.statusCode)
        assertEquals("stats unavailable", (result.body as ErrorResponse).message)
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

    @Test
    fun `leaderboard passes explicit limit to service`() {
        whenever(statsService.leaderboard(10)).thenReturn(emptyList())

        val result = controller.leaderboard(limit = 10)

        assertEquals(HttpStatus.OK, result.statusCode)
        assertEquals(emptyList<LeaderboardEntryDto>(), result.body)
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
