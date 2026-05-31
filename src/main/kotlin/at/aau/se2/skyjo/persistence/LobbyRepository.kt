package at.aau.se2.skyjo.persistence

import at.aau.se2.skyjo.model.lobby.LobbyStatus
import jakarta.annotation.PostConstruct
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Repository

data class LobbyRecord(
    val lobbyId: String,
    val joinCode: String,
    val hostUserId: String,
    val status: LobbyStatus,
    val maxPlayers: Int,
    val createdAt: Long,
    val updatedAt: Long,
)

data class LobbyMemberRecord(
    val lobbyId: String,
    val userId: String,
    val username: String,
    val isHost: Boolean,
    val joinedAt: Long,
)

@Repository
class LobbyRepository(private val jdbc: JdbcTemplate) {

    @PostConstruct
    fun initSchema() {
        jdbc.execute(
            """
            CREATE TABLE IF NOT EXISTS lobbies (
                lobby_id TEXT PRIMARY KEY,
                join_code TEXT NOT NULL COLLATE NOCASE UNIQUE,
                host_user_id TEXT NOT NULL,
                status TEXT NOT NULL,
                max_players INTEGER NOT NULL,
                created_at INTEGER NOT NULL,
                updated_at INTEGER NOT NULL
            )
            """.trimIndent()
        )
        jdbc.execute(
            """
            CREATE TABLE IF NOT EXISTS lobby_members (
                lobby_id TEXT NOT NULL,
                user_id TEXT NOT NULL,
                username TEXT NOT NULL,
                is_host INTEGER NOT NULL,
                joined_at INTEGER NOT NULL,
                PRIMARY KEY(lobby_id, user_id),
                FOREIGN KEY(lobby_id) REFERENCES lobbies(lobby_id)
            )
            """.trimIndent()
        )
    }

    fun createLobby(lobbyId: String, joinCode: String, hostUserId: String, maxPlayers: Int, now: Long) {
        jdbc.update(
            """
            INSERT INTO lobbies (lobby_id, join_code, host_user_id, status, max_players, created_at, updated_at)
            VALUES (?, ?, ?, ?, ?, ?, ?)
            """.trimIndent(),
            lobbyId,
            joinCode,
            hostUserId,
            LobbyStatus.WAITING.name,
            maxPlayers,
            now,
            now,
        )
    }

    fun findLobbyByJoinCode(joinCode: String): LobbyRecord? =
        jdbc.query(LOBBY_SELECT + " WHERE join_code = ? COLLATE NOCASE", ::toLobbyRecord, joinCode)
            .firstOrNull()

    fun findLobbyById(lobbyId: String): LobbyRecord? =
        jdbc.query(LOBBY_SELECT + " WHERE lobby_id = ?", ::toLobbyRecord, lobbyId)
            .firstOrNull()

    fun findCurrentLobbyForUser(userId: String): LobbyRecord? =
        jdbc.query(
            """
            $LOBBY_SELECT
            WHERE lobby_id IN (
                SELECT lobby_id FROM lobby_members WHERE user_id = ?
            )
              AND status IN (?, ?)
            ORDER BY updated_at DESC
            LIMIT 1
            """.trimIndent(),
            ::toLobbyRecord,
            userId,
            LobbyStatus.WAITING.name,
            LobbyStatus.IN_GAME.name,
        ).firstOrNull()

    fun upsertMember(lobbyId: String, userId: String, username: String, isHost: Boolean, joinedAt: Long) {
        jdbc.update(
            """
            INSERT INTO lobby_members (lobby_id, user_id, username, is_host, joined_at)
            VALUES (?, ?, ?, ?, ?)
            ON CONFLICT(lobby_id, user_id) DO UPDATE SET
                username = excluded.username,
                is_host = excluded.is_host,
                joined_at = excluded.joined_at
            """.trimIndent(),
            lobbyId,
            userId,
            username,
            if (isHost) 1 else 0,
            joinedAt,
        )
    }

    fun listMembers(lobbyId: String): List<LobbyMemberRecord> =
        jdbc.query(
            """
            SELECT lobby_id, user_id, username, is_host, joined_at
            FROM lobby_members
            WHERE lobby_id = ?
            ORDER BY joined_at ASC
            """.trimIndent(),
            { rs, _ ->
                LobbyMemberRecord(
                    lobbyId = rs.getString("lobby_id"),
                    userId = rs.getString("user_id"),
                    username = rs.getString("username"),
                    isHost = rs.getInt("is_host") == 1,
                    joinedAt = rs.getLong("joined_at"),
                )
            },
            lobbyId,
        )

    fun removeMember(lobbyId: String, userId: String) {
        jdbc.update("DELETE FROM lobby_members WHERE lobby_id = ? AND user_id = ?", lobbyId, userId)
    }

    fun setHost(lobbyId: String, userId: String, isHost: Boolean) {
        jdbc.update(
            "UPDATE lobby_members SET is_host = ? WHERE lobby_id = ? AND user_id = ?",
            if (isHost) 1 else 0,
            lobbyId,
            userId,
        )
        if (isHost) {
            jdbc.update("UPDATE lobbies SET host_user_id = ? WHERE lobby_id = ?", userId, lobbyId)
        }
    }

    fun updateLobbyStatus(lobbyId: String, status: LobbyStatus, now: Long) {
        jdbc.update(
            "UPDATE lobbies SET status = ?, updated_at = ? WHERE lobby_id = ?",
            status.name,
            now,
            lobbyId,
        )
    }

    private fun toLobbyRecord(rs: java.sql.ResultSet, @Suppress("UNUSED_PARAMETER") rowNum: Int) = LobbyRecord(
        lobbyId = rs.getString("lobby_id"),
        joinCode = rs.getString("join_code"),
        hostUserId = rs.getString("host_user_id"),
        status = LobbyStatus.valueOf(rs.getString("status")),
        maxPlayers = rs.getInt("max_players"),
        createdAt = rs.getLong("created_at"),
        updatedAt = rs.getLong("updated_at"),
    )

    private companion object {
        const val LOBBY_SELECT = """
            SELECT lobby_id, join_code, host_user_id, status, max_players, created_at, updated_at
            FROM lobbies
        """
    }
}
