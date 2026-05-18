package at.aau.se2.skyjo.persistence

import at.aau.se2.skyjo.model.social.LobbyInviteStatus
import jakarta.annotation.PostConstruct
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Repository

data class LobbyInviteRecord(
    val inviteId: String,
    val lobbyId: String,
    val joinCode: String,
    val fromUserId: String,
    val fromUsername: String,
    val toUserId: String,
    val toUsername: String,
    val status: LobbyInviteStatus,
    val createdAt: Long,
    val respondedAt: Long?,
)

@Repository
class LobbyInviteRepository(private val jdbc: JdbcTemplate) {

    @PostConstruct
    fun initSchema() {
        jdbc.execute(
            """
            CREATE TABLE IF NOT EXISTS lobby_invites (
                invite_id TEXT PRIMARY KEY,
                lobby_id TEXT NOT NULL,
                join_code TEXT NOT NULL,
                from_user_id TEXT NOT NULL,
                to_user_id TEXT NOT NULL,
                status TEXT NOT NULL,
                created_at INTEGER NOT NULL,
                responded_at INTEGER,
                FOREIGN KEY(lobby_id) REFERENCES lobbies(lobby_id),
                FOREIGN KEY(from_user_id) REFERENCES users(user_id),
                FOREIGN KEY(to_user_id) REFERENCES users(user_id)
            )
            """.trimIndent(),
        )
    }

    fun createInvite(
        inviteId: String,
        lobbyId: String,
        joinCode: String,
        fromUserId: String,
        toUserId: String,
        now: Long,
    ) {
        jdbc.update(
            """
            INSERT INTO lobby_invites (
                invite_id, lobby_id, join_code, from_user_id, to_user_id, status, created_at, responded_at
            )
            VALUES (?, ?, ?, ?, ?, ?, ?, NULL)
            """.trimIndent(),
            inviteId,
            lobbyId,
            joinCode,
            fromUserId,
            toUserId,
            LobbyInviteStatus.PENDING.name,
            now,
        )
    }

    fun findInviteById(inviteId: String): LobbyInviteRecord? =
        jdbc.query(INVITE_SELECT + " WHERE li.invite_id = ?", ::toRecord, inviteId).firstOrNull()

    fun findPendingInvite(lobbyId: String, toUserId: String): LobbyInviteRecord? =
        jdbc.query(
            INVITE_SELECT + """
            WHERE li.lobby_id = ?
              AND li.to_user_id = ?
              AND li.status = ?
            """.trimIndent(),
            ::toRecord,
            lobbyId,
            toUserId,
            LobbyInviteStatus.PENDING.name,
        ).firstOrNull()

    fun listPendingInvitesForUser(userId: String): List<LobbyInviteRecord> =
        jdbc.query(
            INVITE_SELECT + """
            WHERE li.to_user_id = ?
              AND li.status = ?
            ORDER BY li.created_at DESC
            """.trimIndent(),
            ::toRecord,
            userId,
            LobbyInviteStatus.PENDING.name,
        )

    fun updateInviteStatus(inviteId: String, status: LobbyInviteStatus, respondedAt: Long): LobbyInviteRecord? {
        jdbc.update(
            "UPDATE lobby_invites SET status = ?, responded_at = ? WHERE invite_id = ?",
            status.name,
            respondedAt,
            inviteId,
        )
        return findInviteById(inviteId)
    }

    private fun toRecord(rs: java.sql.ResultSet, @Suppress("UNUSED_PARAMETER") rowNum: Int) =
        LobbyInviteRecord(
            inviteId = rs.getString("invite_id"),
            lobbyId = rs.getString("lobby_id"),
            joinCode = rs.getString("join_code"),
            fromUserId = rs.getString("from_user_id"),
            fromUsername = rs.getString("from_username"),
            toUserId = rs.getString("to_user_id"),
            toUsername = rs.getString("to_username"),
            status = LobbyInviteStatus.valueOf(rs.getString("status")),
            createdAt = rs.getLong("created_at"),
            respondedAt = rs.getNullableLong("responded_at"),
        )

    private fun java.sql.ResultSet.getNullableLong(column: String): Long? {
        val value = getLong(column)
        return if (wasNull()) null else value
    }

    private companion object {
        const val INVITE_SELECT = """
            SELECT li.invite_id,
                   li.lobby_id,
                   li.join_code,
                   li.from_user_id,
                   from_user.username AS from_username,
                   li.to_user_id,
                   to_user.username AS to_username,
                   li.status,
                   li.created_at,
                   li.responded_at
            FROM lobby_invites li
            JOIN users from_user ON from_user.user_id = li.from_user_id
            JOIN users to_user ON to_user.user_id = li.to_user_id
        """
    }
}
