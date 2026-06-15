package at.aau.se2.skyjo.persistence

import jakarta.annotation.PostConstruct
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Repository

data class AuthUserRecord(
    val userId: String,
    val username: String,
    val passwordHash: String,
    val createdAt: Long,
    val lastSeen: Long,
)

data class AuthSessionRecord(
    val tokenHash: String,
    val userId: String,
    val createdAt: Long,
    val expiresAt: Long,
    val revokedAt: Long?,
    val lastSeen: Long,
)

data class WebSocketTicketRecord(
    val ticketHash: String,
    val userId: String,
    val createdAt: Long,
    val expiresAt: Long,
    val consumedAt: Long?,
)

data class UserPresenceRecord(
    val userId: String,
    val connected: Boolean,
    val currentLobbyId: String?,
    val updatedAt: Long,
    val lastSeenAt: Long,
)

@Repository
class AuthRepository(private val jdbc: JdbcTemplate) {

    @PostConstruct
    fun initSchema() {
        jdbc.execute(
            """
            CREATE TABLE IF NOT EXISTS users (
                user_id TEXT PRIMARY KEY,
                username TEXT NOT NULL COLLATE NOCASE UNIQUE,
                password_hash TEXT NOT NULL,
                created_at INTEGER NOT NULL,
                last_seen INTEGER NOT NULL
            )
            """.trimIndent()
        )
        jdbc.execute(
            """
            CREATE TABLE IF NOT EXISTS sessions (
                token_hash TEXT PRIMARY KEY,
                user_id TEXT NOT NULL,
                created_at INTEGER NOT NULL,
                expires_at INTEGER NOT NULL,
                revoked_at INTEGER,
                last_seen INTEGER NOT NULL,
                FOREIGN KEY(user_id) REFERENCES users(user_id)
            )
            """.trimIndent()
        )
        jdbc.execute(
            """
            CREATE TABLE IF NOT EXISTS websocket_tickets (
                ticket_hash TEXT PRIMARY KEY,
                user_id TEXT NOT NULL,
                created_at INTEGER NOT NULL,
                expires_at INTEGER NOT NULL,
                consumed_at INTEGER,
                FOREIGN KEY(user_id) REFERENCES users(user_id)
            )
            """.trimIndent()
        )
        jdbc.execute(
            """
            CREATE TABLE IF NOT EXISTS user_presence (
                user_id TEXT PRIMARY KEY,
                connected INTEGER NOT NULL DEFAULT 0,
                current_lobby_id TEXT,
                updated_at INTEGER NOT NULL,
                last_seen_at INTEGER NOT NULL DEFAULT 0,
                FOREIGN KEY(user_id) REFERENCES users(user_id)
            )
            """.trimIndent()
        )
        runCatching {
            jdbc.execute("ALTER TABLE user_presence ADD COLUMN last_seen_at INTEGER NOT NULL DEFAULT 0")
        }
    }

    fun createUser(userId: String, username: String, passwordHash: String, now: Long) {
        jdbc.update(
            """
            INSERT INTO users (user_id, username, password_hash, created_at, last_seen)
            VALUES (?, ?, ?, ?, ?)
            """.trimIndent(),
            userId,
            username,
            passwordHash,
            now,
            now,
        )
    }

    fun findUserByUsername(username: String): AuthUserRecord? =
        jdbc.query(
            """
            SELECT user_id, username, password_hash, created_at, last_seen
            FROM users
            WHERE username = ? COLLATE NOCASE
            """.trimIndent(),
            { rs, _ ->
                AuthUserRecord(
                    userId = rs.getString("user_id"),
                    username = rs.getString("username"),
                    passwordHash = rs.getString("password_hash"),
                    createdAt = rs.getLong("created_at"),
                    lastSeen = rs.getLong("last_seen"),
                )
            },
            username,
        ).firstOrNull()

    fun findUserById(userId: String): AuthUserRecord? =
        jdbc.query(
            """
            SELECT user_id, username, password_hash, created_at, last_seen
            FROM users
            WHERE user_id = ?
            """.trimIndent(),
            { rs, _ ->
                AuthUserRecord(
                    userId = rs.getString("user_id"),
                    username = rs.getString("username"),
                    passwordHash = rs.getString("password_hash"),
                    createdAt = rs.getLong("created_at"),
                    lastSeen = rs.getLong("last_seen"),
                )
            },
            userId,
        ).firstOrNull()

    fun createSession(tokenHash: String, userId: String, createdAt: Long, expiresAt: Long) {
        jdbc.update(
            """
            INSERT INTO sessions (token_hash, user_id, created_at, expires_at, revoked_at, last_seen)
            VALUES (?, ?, ?, ?, NULL, ?)
            """.trimIndent(),
            tokenHash,
            userId,
            createdAt,
            expiresAt,
            createdAt,
        )
    }

    fun findActiveSession(tokenHash: String, now: Long): AuthSessionRecord? =
        jdbc.query(
            """
            SELECT token_hash, user_id, created_at, expires_at, revoked_at, last_seen
            FROM sessions
            WHERE token_hash = ?
              AND revoked_at IS NULL
              AND expires_at > ?
            """.trimIndent(),
            { rs, _ -> rs.toSessionRecord() },
            tokenHash,
            now,
        ).firstOrNull()

    fun touchSession(tokenHash: String, now: Long) {
        jdbc.update(
            "UPDATE sessions SET last_seen = ? WHERE token_hash = ? AND revoked_at IS NULL",
            now,
            tokenHash,
        )
    }

    fun revokeSession(tokenHash: String, now: Long) {
        jdbc.update(
            "UPDATE sessions SET revoked_at = ? WHERE token_hash = ? AND revoked_at IS NULL",
            now,
            tokenHash,
        )
    }

    fun createWebSocketTicket(ticketHash: String, userId: String, createdAt: Long, expiresAt: Long) {
        jdbc.update(
            """
            INSERT INTO websocket_tickets (ticket_hash, user_id, created_at, expires_at, consumed_at)
            VALUES (?, ?, ?, ?, NULL)
            """.trimIndent(),
            ticketHash,
            userId,
            createdAt,
            expiresAt,
        )
    }

    fun consumeWebSocketTicket(ticketHash: String, now: Long): WebSocketTicketRecord? {
        val ticket = jdbc.query(
            """
            SELECT ticket_hash, user_id, created_at, expires_at, consumed_at
            FROM websocket_tickets
            WHERE ticket_hash = ?
              AND consumed_at IS NULL
              AND expires_at > ?
            """.trimIndent(),
            { rs, _ ->
                WebSocketTicketRecord(
                    ticketHash = rs.getString("ticket_hash"),
                    userId = rs.getString("user_id"),
                    createdAt = rs.getLong("created_at"),
                    expiresAt = rs.getLong("expires_at"),
                    consumedAt = rs.getNullableLong("consumed_at"),
                )
            },
            ticketHash,
            now,
        ).firstOrNull() ?: return null

        val updated = jdbc.update(
            """
            UPDATE websocket_tickets
            SET consumed_at = ?
            WHERE ticket_hash = ?
              AND consumed_at IS NULL
              AND expires_at > ?
            """.trimIndent(),
            now,
            ticketHash,
            now,
        )

        return if (updated == 1) ticket.copy(consumedAt = now) else null
    }

    fun setPresence(userId: String, connected: Boolean, currentLobbyId: String?, now: Long) {
        jdbc.update(
            """
            INSERT INTO user_presence (user_id, connected, current_lobby_id, updated_at, last_seen_at)
            VALUES (?, ?, ?, ?, ?)
            ON CONFLICT(user_id) DO UPDATE SET
                connected = excluded.connected,
                current_lobby_id = excluded.current_lobby_id,
                updated_at = excluded.updated_at,
                last_seen_at = excluded.last_seen_at
            """.trimIndent(),
            userId,
            if (connected) 1 else 0,
            currentLobbyId,
            now,
            now,
        )
    }

    /**
     * Refreshes a user's presence without clobbering their current lobby. Used by the
     * foreground heartbeat so a user counts as online while in the app, even when not
     * connected to a lobby websocket.
     */
    fun touchPresence(userId: String, now: Long) {
        jdbc.update(
            """
            INSERT INTO user_presence (user_id, connected, current_lobby_id, updated_at, last_seen_at)
            VALUES (?, 1, NULL, ?, ?)
            ON CONFLICT(user_id) DO UPDATE SET
                connected = 1,
                updated_at = excluded.updated_at,
                last_seen_at = excluded.last_seen_at
            """.trimIndent(),
            userId,
            now,
            now,
        )
    }

    fun clearCurrentLobby(lobbyId: String, now: Long) {
        jdbc.update(
            """
            UPDATE user_presence
            SET current_lobby_id = NULL,
                updated_at = ?
            WHERE current_lobby_id = ?
            """.trimIndent(),
            now,
            lobbyId,
        )
    }

    fun getPresence(userId: String): UserPresenceRecord? =
        jdbc.query(
            """
            SELECT user_id, connected, current_lobby_id, updated_at, last_seen_at
            FROM user_presence
            WHERE user_id = ?
            """.trimIndent(),
            { rs, _ ->
                UserPresenceRecord(
                    userId = rs.getString("user_id"),
                    connected = rs.getInt("connected") == 1,
                    currentLobbyId = rs.getString("current_lobby_id"),
                    updatedAt = rs.getLong("updated_at"),
                    lastSeenAt = rs.getLong("last_seen_at"),
                )
            },
            userId,
        ).firstOrNull()

    private fun java.sql.ResultSet.toSessionRecord() = AuthSessionRecord(
        tokenHash = getString("token_hash"),
        userId = getString("user_id"),
        createdAt = getLong("created_at"),
        expiresAt = getLong("expires_at"),
        revokedAt = getNullableLong("revoked_at"),
        lastSeen = getLong("last_seen"),
    )

    private fun java.sql.ResultSet.getNullableLong(column: String): Long? {
        val value = getLong(column)
        return if (wasNull()) null else value
    }
}
