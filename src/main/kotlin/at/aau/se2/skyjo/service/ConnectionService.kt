package at.aau.se2.skyjo.service

import org.springframework.stereotype.Service
import java.util.concurrent.ConcurrentHashMap

@Service
class ConnectionService {

    data class SessionInfo(val playerName: String, val gameId: String?)

    private val sessions: ConcurrentHashMap<String, SessionInfo> = ConcurrentHashMap()

    fun registerSession(sessionId: String, playerName: String, gameId: String? = null) {
        sessions[sessionId] = SessionInfo(playerName, gameId)
    }

    fun removeSession(sessionId: String): String? = sessions.remove(sessionId)?.playerName

    fun getPlayerName(sessionId: String): String? = sessions[sessionId]?.playerName

    fun getGameId(sessionId: String): String? = sessions[sessionId]?.gameId

    fun getConnectedCount(): Int = sessions.size

    fun isConnected(sessionId: String): Boolean = sessions.containsKey(sessionId)
}
