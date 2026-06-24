package at.aau.se2.skyjo.persistence

import at.aau.se2.skyjo.model.social.FriendRequestStatus
import jakarta.annotation.PostConstruct
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Repository

data class FriendUserRecord(
    val userId: String,
    val username: String,
    val online: Boolean,
    val currentLobbyId: String?,
)

data class FriendRequestRecord(
    val requestId: String,
    val fromUserId: String,
    val fromUsername: String,
    val toUserId: String,
    val toUsername: String,
    val status: FriendRequestStatus,
    val createdAt: Long,
    val respondedAt: Long?,
)

@Repository
class FriendRepository(private val jdbc: JdbcTemplate) {

    @PostConstruct
    fun initSchema() {
        jdbc.execute(
            """
            CREATE TABLE IF NOT EXISTS friend_requests (
                request_id TEXT PRIMARY KEY,
                from_user_id TEXT NOT NULL,
                to_user_id TEXT NOT NULL,
                status TEXT NOT NULL,
                created_at INTEGER NOT NULL,
                responded_at INTEGER,
                UNIQUE(from_user_id, to_user_id),
                FOREIGN KEY(from_user_id) REFERENCES users(user_id),
                FOREIGN KEY(to_user_id) REFERENCES users(user_id)
            )
            """.trimIndent(),
        )
        jdbc.execute(
            """
            CREATE TABLE IF NOT EXISTS friendships (
                user_id TEXT NOT NULL,
                friend_user_id TEXT NOT NULL,
                created_at INTEGER NOT NULL,
                PRIMARY KEY(user_id, friend_user_id),
                FOREIGN KEY(user_id) REFERENCES users(user_id),
                FOREIGN KEY(friend_user_id) REFERENCES users(user_id)
            )
            """.trimIndent(),
        )
    }

    fun searchUsers(query: String, currentUserId: String, limit: Int): List<FriendUserRecord> =
        jdbc.query(
            """
            SELECT u.user_id, u.username, COALESCE(p.connected, 0) AS connected, p.current_lobby_id
            FROM users u
            LEFT JOIN user_presence p ON p.user_id = u.user_id
            WHERE u.user_id != ?
              AND u.username LIKE ? COLLATE NOCASE
            ORDER BY u.username COLLATE NOCASE ASC
            LIMIT ?
            """.trimIndent(),
            ::toFriendUserRecord,
            currentUserId,
            "%${query.trim()}%",
            limit,
        )

    fun createFriendRequest(requestId: String, fromUserId: String, toUserId: String, now: Long) {
        jdbc.update(
            """
            INSERT INTO friend_requests (request_id, from_user_id, to_user_id, status, created_at, responded_at)
            VALUES (?, ?, ?, ?, ?, NULL)
            ON CONFLICT(from_user_id, to_user_id) DO UPDATE SET
                request_id = excluded.request_id,
                status = excluded.status,
                created_at = excluded.created_at,
                responded_at = NULL
            """.trimIndent(),
            requestId,
            fromUserId,
            toUserId,
            FriendRequestStatus.PENDING.name,
            now,
        )
    }

    fun findRequestById(requestId: String): FriendRequestRecord? =
        jdbc.query(REQUEST_SELECT + " WHERE fr.request_id = ?", ::toFriendRequestRecord, requestId).firstOrNull()

    fun findPendingRequest(fromUserId: String, toUserId: String): FriendRequestRecord? =
        jdbc.query(
            REQUEST_SELECT + """
            WHERE fr.from_user_id = ?
              AND fr.to_user_id = ?
              AND fr.status = ?
            """.trimIndent(),
            ::toFriendRequestRecord,
            fromUserId,
            toUserId,
            FriendRequestStatus.PENDING.name,
        ).firstOrNull()

    fun listIncomingRequests(userId: String): List<FriendRequestRecord> =
        jdbc.query(
            REQUEST_SELECT + """
            WHERE fr.to_user_id = ?
              AND fr.status = ?
            ORDER BY fr.created_at DESC
            """.trimIndent(),
            ::toFriendRequestRecord,
            userId,
            FriendRequestStatus.PENDING.name,
        )

    fun listOutgoingRequests(userId: String): List<FriendRequestRecord> =
        jdbc.query(
            REQUEST_SELECT + """
            WHERE fr.from_user_id = ?
              AND fr.status = ?
            ORDER BY fr.created_at DESC
            """.trimIndent(),
            ::toFriendRequestRecord,
            userId,
            FriendRequestStatus.PENDING.name,
        )

    fun updateRequestStatus(requestId: String, status: FriendRequestStatus, respondedAt: Long): FriendRequestRecord? {
        jdbc.update(
            "UPDATE friend_requests SET status = ?, responded_at = ? WHERE request_id = ?",
            status.name,
            respondedAt,
            requestId,
        )
        return findRequestById(requestId)
    }

    fun createFriendship(userId: String, friendUserId: String, now: Long) {
        jdbc.update(
            """
            INSERT OR IGNORE INTO friendships (user_id, friend_user_id, created_at)
            VALUES (?, ?, ?)
            """.trimIndent(),
            userId,
            friendUserId,
            now,
        )
    }

    fun areFriends(userId: String, friendUserId: String): Boolean =
        jdbc.queryForObject(
            """
            SELECT COUNT(*)
            FROM friendships
            WHERE user_id = ?
              AND friend_user_id = ?
            """.trimIndent(),
            Int::class.java,
            userId,
            friendUserId,
        ) != 0

    /**
     * Lists a user's friends. A friend counts as online only when their presence is
     * connected AND was refreshed at or after [onlineSince] (now minus the presence TTL),
     * so stale presence rows from crashed/backgrounded clients no longer read as online.
     * Lobby ids are hidden under the same freshness check to avoid exposing stale lobbies.
     */
    fun listFriends(userId: String, onlineSince: Long): List<FriendUserRecord> =
        jdbc.query(
            """
            SELECT u.user_id, u.username,
                   CASE WHEN p.connected = 1 AND p.last_seen_at >= ? THEN 1 ELSE 0 END AS connected,
                   CASE WHEN p.connected = 1 AND p.last_seen_at >= ? THEN p.current_lobby_id ELSE NULL END AS current_lobby_id
            FROM friendships f
            JOIN users u ON u.user_id = f.friend_user_id
            LEFT JOIN user_presence p ON p.user_id = u.user_id
            WHERE f.user_id = ?
            ORDER BY u.username COLLATE NOCASE ASC
            """.trimIndent(),
            ::toFriendUserRecord,
            onlineSince,
            onlineSince,
            userId,
        )

    private fun toFriendUserRecord(rs: java.sql.ResultSet, @Suppress("UNUSED_PARAMETER") rowNum: Int) =
        FriendUserRecord(
            userId = rs.getString("user_id"),
            username = rs.getString("username"),
            online = rs.getInt("connected") == 1,
            currentLobbyId = rs.getString("current_lobby_id"),
        )

    private fun toFriendRequestRecord(rs: java.sql.ResultSet, @Suppress("UNUSED_PARAMETER") rowNum: Int) =
        FriendRequestRecord(
            requestId = rs.getString("request_id"),
            fromUserId = rs.getString("from_user_id"),
            fromUsername = rs.getString("from_username"),
            toUserId = rs.getString("to_user_id"),
            toUsername = rs.getString("to_username"),
            status = FriendRequestStatus.valueOf(rs.getString("status")),
            createdAt = rs.getLong("created_at"),
            respondedAt = rs.getNullableLong("responded_at"),
        )

    private fun java.sql.ResultSet.getNullableLong(column: String): Long? {
        val value = getLong(column)
        return if (wasNull()) null else value
    }

    private companion object {
        const val REQUEST_SELECT = """
            SELECT fr.request_id,
                   fr.from_user_id,
                   from_user.username AS from_username,
                   fr.to_user_id,
                   to_user.username AS to_username,
                   fr.status,
                   fr.created_at,
                   fr.responded_at
            FROM friend_requests fr
            JOIN users from_user ON from_user.user_id = fr.from_user_id
            JOIN users to_user ON to_user.user_id = fr.to_user_id
        """
    }
}
