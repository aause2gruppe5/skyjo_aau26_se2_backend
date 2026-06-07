package at.aau.se2.skyjo.persistence

import at.aau.se2.skyjo.game.model.BoardPosition
import at.aau.se2.skyjo.game.model.GamePhase
import at.aau.se2.skyjo.game.model.GameState
import com.fasterxml.jackson.core.JsonGenerator
import com.fasterxml.jackson.databind.DeserializationContext
import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.databind.KeyDeserializer
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.SerializerProvider
import com.fasterxml.jackson.databind.jsontype.BasicPolymorphicTypeValidator
import com.fasterxml.jackson.databind.module.SimpleModule
import com.fasterxml.jackson.databind.ser.std.StdSerializer
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import jakarta.annotation.PostConstruct
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Repository

data class PersistedGame(
    val gameId: String,
    val lobbyId: String?,
    val state: GameState,
)

@Repository
class GameRepository(private val jdbc: JdbcTemplate) {

    private val mapper: ObjectMapper = jacksonObjectMapper().apply {
        configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)
        val module = SimpleModule()
        module.addKeySerializer(BoardPosition::class.java, object : StdSerializer<BoardPosition>(BoardPosition::class.java) {
            override fun serialize(value: BoardPosition, gen: JsonGenerator, ser: SerializerProvider) {
                gen.writeFieldName("${value.row},${value.column}")
            }
        })
        module.addKeyDeserializer(BoardPosition::class.java, object : KeyDeserializer() {
            override fun deserializeKey(key: String, ctx: DeserializationContext): Any {
                val (r, c) = key.split(",").map(String::toInt)
                return BoardPosition(r, c)
            }
        })
        registerModule(module)
        activateDefaultTypingAsProperty(
            BasicPolymorphicTypeValidator.builder()
                .allowIfSubType("at.aau.se2.skyjo")
                .allowIfSubType("java.util")
                .allowIfSubType("kotlin.collections")
                .allowIfSubTypeIsArray()
                .build(),
            ObjectMapper.DefaultTyping.NON_CONCRETE_AND_ARRAYS,
            "@t"
        )
    }

    @PostConstruct
    fun initSchema() {
        jdbc.execute(
            """
            CREATE TABLE IF NOT EXISTS games (
                game_id TEXT PRIMARY KEY,
                lobby_id TEXT,
                state_json TEXT NOT NULL,
                phase TEXT NOT NULL,
                completed INTEGER NOT NULL DEFAULT 0,
                updated_at INTEGER NOT NULL
            )
            """.trimIndent()
        )
        runCatching { jdbc.execute("ALTER TABLE games ADD COLUMN lobby_id TEXT") }
        runCatching { jdbc.execute("ALTER TABLE games ADD COLUMN completed INTEGER NOT NULL DEFAULT 0") }
        jdbc.execute(
            """
            CREATE TABLE IF NOT EXISTS player_sessions (
                player_name TEXT PRIMARY KEY,
                game_id TEXT NOT NULL,
                connected INTEGER NOT NULL DEFAULT 0,
                last_seen INTEGER NOT NULL
            )
            """.trimIndent()
        )
    }

    fun saveGame(gameId: String, state: GameState, completed: Boolean = false) {
        saveGame(gameId, lobbyId = null, state = state, completed = completed)
    }

    fun saveGame(gameId: String, lobbyId: String?, state: GameState, completed: Boolean = false) {
        val json = mapper.writeValueAsString(state)
        jdbc.update(
            """
            INSERT INTO games (game_id, lobby_id, state_json, phase, completed, updated_at)
            VALUES (?, ?, ?, ?, ?, ?)
            ON CONFLICT(game_id) DO UPDATE SET
                lobby_id = excluded.lobby_id,
                state_json = excluded.state_json,
                phase = excluded.phase,
                completed = excluded.completed,
                updated_at = excluded.updated_at
            """.trimIndent(),
            gameId, lobbyId, json, state.phase.name, if (completed) 1 else 0, System.currentTimeMillis()
        )
    }

    fun loadActiveGame(): Pair<String, GameState>? =
        loadActiveGames().firstOrNull()?.let { it.gameId to it.state }

    fun loadActiveGames(): List<PersistedGame> {
        return try {
            jdbc.query(
                "SELECT game_id, lobby_id, state_json FROM games WHERE completed = 0 AND phase != ? ORDER BY updated_at ASC",
                { rs, _ ->
                    PersistedGame(
                        gameId = rs.getString("game_id"),
                        lobbyId = rs.getString("lobby_id"),
                        state = mapper.readValue(rs.getString("state_json"), GameState::class.java),
                    )
                },
                GamePhase.NOT_STARTED.name // WICHTIG: Hier darf nur noch DIESER EINE Parameter stehen!
            )
        } catch (_: Exception) {
            emptyList()
        }
    }

    fun savePlayerSession(playerName: String, gameId: String, connected: Boolean) {
        jdbc.update(
            """
            INSERT INTO player_sessions (player_name, game_id, connected, last_seen)
            VALUES (?, ?, ?, ?)
            ON CONFLICT(player_name) DO UPDATE SET
                game_id = excluded.game_id,
                connected = excluded.connected,
                last_seen = excluded.last_seen
            """.trimIndent(),
            playerName, gameId, if (connected) 1 else 0, System.currentTimeMillis()
        )
    }

    fun markDisconnected(playerName: String) {
        jdbc.update(
            "UPDATE player_sessions SET connected = 0, last_seen = ? WHERE player_name = ?",
            System.currentTimeMillis(), playerName
        )
    }

    fun deletePlayerSessionsForGame(gameId: String) {
        jdbc.update("DELETE FROM player_sessions WHERE game_id = ?", gameId)
    }

    fun getPlayerGame(playerName: String): String? {
        return try {
            jdbc.query(
                """
                SELECT player_sessions.game_id
                FROM player_sessions
                JOIN games ON games.game_id = player_sessions.game_id
                WHERE player_sessions.player_name = ?
                  AND games.completed = 0
                  AND games.phase != ?
                """.trimIndent(),
                { rs, _ -> rs.getString("game_id") },
                playerName,
                GamePhase.NOT_STARTED.name
            ).firstOrNull()
        } catch (_: Exception) {
            null
        }
    }
}
