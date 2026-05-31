package at.aau.se2.skyjo.persistence

import jakarta.annotation.PostConstruct
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Repository

data class PlayerStatsRecord(
    val userId: String,
    val username: String,
    val gamesPlayed: Int,
    val wins: Int,
    val totalScore: Int,
    val bestScore: Int?,
    val averageScore: Double,
    val updatedAt: Long,
)

@Repository
class StatsRepository(private val jdbc: JdbcTemplate) {

    @PostConstruct
    fun initSchema() {
        jdbc.execute(
            """
            CREATE TABLE IF NOT EXISTS player_stats (
                user_id TEXT PRIMARY KEY,
                games_played INTEGER NOT NULL DEFAULT 0,
                wins INTEGER NOT NULL DEFAULT 0,
                total_score INTEGER NOT NULL DEFAULT 0,
                best_score INTEGER,
                updated_at INTEGER NOT NULL,
                FOREIGN KEY(user_id) REFERENCES users(user_id)
            )
            """.trimIndent(),
        )
    }

    fun recordResult(userId: String, totalScore: Int, won: Boolean, now: Long) {
        jdbc.update(
            """
            INSERT INTO player_stats (user_id, games_played, wins, total_score, best_score, updated_at)
            VALUES (?, 1, ?, ?, ?, ?)
            ON CONFLICT(user_id) DO UPDATE SET
                games_played = player_stats.games_played + 1,
                wins = player_stats.wins + excluded.wins,
                total_score = player_stats.total_score + excluded.total_score,
                best_score = CASE
                    WHEN player_stats.best_score IS NULL OR excluded.best_score < player_stats.best_score
                    THEN excluded.best_score
                    ELSE player_stats.best_score
                END,
                updated_at = excluded.updated_at
            """.trimIndent(),
            userId,
            if (won) 1 else 0,
            totalScore,
            totalScore,
            now,
        )
    }

    fun findStats(userId: String): PlayerStatsRecord? =
        jdbc.query(
            STATS_SELECT + " WHERE ps.user_id = ?",
            ::toRecord,
            userId,
        ).firstOrNull()

    fun leaderboard(limit: Int): List<PlayerStatsRecord> =
        jdbc.query(
            """
            $STATS_SELECT
            WHERE ps.games_played > 0
            ORDER BY average_score ASC,
                     ps.wins DESC,
                     ps.games_played DESC,
                     u.username COLLATE NOCASE ASC
            LIMIT ?
            """.trimIndent(),
            ::toRecord,
            limit,
        )

    private fun toRecord(rs: java.sql.ResultSet, @Suppress("UNUSED_PARAMETER") rowNum: Int) =
        PlayerStatsRecord(
            userId = rs.getString("user_id"),
            username = rs.getString("username"),
            gamesPlayed = rs.getInt("games_played"),
            wins = rs.getInt("wins"),
            totalScore = rs.getInt("total_score"),
            bestScore = rs.getNullableInt("best_score"),
            averageScore = rs.getDouble("average_score"),
            updatedAt = rs.getLong("updated_at"),
        )

    private fun java.sql.ResultSet.getNullableInt(column: String): Int? {
        val value = getInt(column)
        return if (wasNull()) null else value
    }

    private companion object {
        const val STATS_SELECT = """
            SELECT ps.user_id,
                   u.username,
                   ps.games_played,
                   ps.wins,
                   ps.total_score,
                   ps.best_score,
                   CAST(ps.total_score AS REAL) / ps.games_played AS average_score,
                   ps.updated_at
            FROM player_stats ps
            JOIN users u ON u.user_id = ps.user_id
        """
    }
}
