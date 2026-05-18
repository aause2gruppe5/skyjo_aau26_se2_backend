package at.aau.se2.skyjo.service

import at.aau.se2.skyjo.model.lobby.LobbyPlayer
import at.aau.se2.skyjo.model.lobby.LobbyState
import at.aau.se2.skyjo.model.lobby.LobbyStatus
import org.springframework.stereotype.Service
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

@Service
class LobbyService {

    private val lock = ReentrantLock()
    private var state = LobbyState()

    fun join(sessionId: String, nickname: String): LobbyState = lock.withLock {
        if (state.status == LobbyStatus.IN_GAME) {
            error("cannot join: game already in progress")
        }
        if (state.players.size >= state.maxPlayers) {
            error("cannot join: lobby is full (max ${state.maxPlayers} players)")
        }
        if (state.players.any { it.sessionId == sessionId }) {
            return state
        }

        val isHost = state.players.isEmpty()
        val player = LobbyPlayer(sessionId = sessionId, nickname = nickname, isHost = isHost)
        state = state.copy(players = state.players + player)
        state
    }

    fun leave(sessionId: String): LobbyState = lock.withLock {
        val players = state.players.filter { it.sessionId != sessionId }
        val reassigned = if (players.isNotEmpty() && players.none { it.isHost }) {
            players.mapIndexed { i, p -> if (i == 0) p.copy(isHost = true) else p }
        } else {
            players
        }
        val newStatus = if (reassigned.isEmpty()) LobbyStatus.WAITING else state.status
        state = state.copy(players = reassigned, status = newStatus)
        state
    }

    fun startGame(sessionId: String): LobbyState = lock.withLock {
        val caller = state.players.find { it.sessionId == sessionId }
            ?: error("player not in lobby")
        if (!caller.isHost) {
            error("only the host can start the game")
        }
        if (state.players.size < 2) {
            error("need at least 2 players to start")
        }
        state = state.copy(status = LobbyStatus.IN_GAME)
        state
    }

    fun reset(): LobbyState = lock.withLock {
        state = LobbyState()
        state
    }

    fun getState(): LobbyState = lock.withLock { state }

    fun isPlayerInLobby(sessionId: String): Boolean = lock.withLock {
        state.players.any { it.sessionId == sessionId }
    }
}
