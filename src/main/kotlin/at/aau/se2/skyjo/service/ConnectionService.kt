package at.aau.se2.skyjo.service

import org.springframework.stereotype.Service
import java.util.concurrent.ConcurrentHashMap

@Service
class ConnectionService {

    private val sessions: ConcurrentHashMap<String, String> = ConcurrentHashMap()

    fun registerSession(sessionId: String, playerName: String) {
        sessions[sessionId] = playerName
    }

    fun removeSession(sessionId: String): String? = sessions.remove(sessionId)

    fun getPlayerName(sessionId: String): String? = sessions[sessionId]

    fun getConnectedCount(): Int = sessions.size

    fun isConnected(sessionId: String): Boolean = sessions.containsKey(sessionId)
}
