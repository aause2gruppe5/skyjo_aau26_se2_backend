package at.aau.se2.skyjo.service

import at.aau.se2.skyjo.model.stats.LeaderboardEntryDto
import at.aau.se2.skyjo.model.stats.PlayerStatsDto
import at.aau.se2.skyjo.persistence.AuthRepository
import at.aau.se2.skyjo.persistence.PlayerStatsRecord
import at.aau.se2.skyjo.persistence.StatsRepository
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.stereotype.Service

@Service
class StatsService @Autowired constructor(
    private val repository: StatsRepository,
    private val authRepository: AuthRepository,
) {

    private var nowProvider: () -> Long = { System.currentTimeMillis() }

    internal constructor(
        repository: StatsRepository,
        authRepository: AuthRepository,
        nowProvider: () -> Long,
    ) : this(repository, authRepository) {
        this.nowProvider = nowProvider
    }

    fun recordGameResult(gameId: String, totalScores: Map<String, Int>) {
        if (totalScores.isEmpty()) {
            return
        }
        val winningScore = totalScores.values.min()
        val winners = totalScores.filterValues { it == winningScore }.keys
        val now = nowProvider()

        totalScores.forEach { (userId, score) ->
            repository.recordResult(userId, totalScore = score, won = userId in winners, now = now)
        }
    }

    fun getStats(userId: String): PlayerStatsDto {
        repository.findStats(userId)?.let { return it.toDto() }
        val user = authRepository.findUserById(userId) ?: throw UnauthorizedException()
        return PlayerStatsDto(
            userId = user.userId,
            username = user.username,
            gamesPlayed = 0,
            wins = 0,
            totalScore = 0,
            bestScore = null,
            averageScore = 0.0,
        )
    }

    fun leaderboard(limit: Int): List<LeaderboardEntryDto> =
        repository.leaderboard(limit.coerceIn(1, 100)).mapIndexed { index, record ->
            LeaderboardEntryDto(
                rank = index + 1,
                userId = record.userId,
                username = record.username,
                averageScore = record.averageScore,
                wins = record.wins,
                gamesPlayed = record.gamesPlayed,
                bestScore = record.bestScore,
                totalScore = record.totalScore,
            )
        }

    private fun PlayerStatsRecord.toDto() =
        PlayerStatsDto(
            userId = userId,
            username = username,
            gamesPlayed = gamesPlayed,
            wins = wins,
            totalScore = totalScore,
            bestScore = bestScore,
            averageScore = averageScore,
        )
}
