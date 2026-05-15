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
                state_json TEXT NOT NULL,
                phase TEXT NOT NULL,
                updated_at INTEGER NOT NULL
            )
            """.trimIndent()
        )
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

    fun saveGame(gameId: String, state: GameState) {
        val json = mapper.writeValueAsString(state)
        jdbc.update(
            """
            INSERT INTO games (game_id, state_json, phase, updated_at)
            VALUES (?, ?, ?, ?)
            ON CONFLICT(game_id) DO UPDATE SET
                state_json = excluded.state_json,
                phase = excluded.phase,
                updated_at = excluded.updated_at
            """.trimIndent(),
            gameId, json, state.phase.name, System.currentTimeMillis()
        )
    }

    fun loadActiveGame(): Pair<String, GameState>? {
        return try {
            jdbc.query(
                "SELECT game_id, state_json FROM games WHERE phase != ? LIMIT 1",
                { rs, _ -> rs.getString("game_id") to rs.getString("state_json") },
                GamePhase.NOT_STARTED.name
            ).firstOrNull()?.let { (gameId, json) ->
                gameId to mapper.readValue(json, GameState::class.java)
            }
        } catch (_: Exception) {
            null
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

    fun getPlayerGame(playerName: String): String? {
        return try {
            jdbc.query(
                "SELECT game_id FROM player_sessions WHERE player_name = ?",
                { rs, _ -> rs.getString("game_id") },
                playerName
            ).firstOrNull()
        } catch (_: Exception) {
            null
        }
    }
}
