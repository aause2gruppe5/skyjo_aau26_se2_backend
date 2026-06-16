package at.aau.se2.skyjo.event

import at.aau.se2.skyjo.model.LobbyPlayerInfo
import at.aau.se2.skyjo.model.LobbyUpdateMessage
import at.aau.se2.skyjo.model.GameUpdateMessage
import at.aau.se2.skyjo.model.lobby.LobbyState
import at.aau.se2.skyjo.service.AuthService
import at.aau.se2.skyjo.service.GameService
import at.aau.se2.skyjo.service.LobbyService
import org.slf4j.LoggerFactory
import org.springframework.context.event.EventListener
import org.springframework.messaging.simp.SimpMessageSendingOperations
import org.springframework.messaging.simp.SimpMessageHeaderAccessor
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import org.springframework.web.socket.messaging.SessionConnectedEvent
import org.springframework.web.socket.messaging.SessionDisconnectEvent
import java.util.concurrent.ConcurrentHashMap

@Component
class WebSocketEventListener(
    private val messagingTemplate: SimpMessageSendingOperations,
    private val lobbyService: LobbyService,
    private val gameService: GameService?,
    private val authService: AuthService? = null,
) {

    private val logger = LoggerFactory.getLogger(WebSocketEventListener::class.java)
    private val activeWebSocketUsersBySessionId = ConcurrentHashMap<String, String>()
    private val activeWebSocketSessionsByUser = ConcurrentHashMap<String, Int>()

    @EventListener
    fun handleWebSocketConnectListener(event: SessionConnectedEvent) {
        val userId = event.user?.name
        logger.info("New WebSocket connection: principal=$userId")
        if (userId != null) {
            markSessionConnected(userId, event.sessionId())
            refreshPresence(userId)
        }
    }

    @EventListener
    fun handleWebSocketDisconnectListener(event: SessionDisconnectEvent) {
        val playerId = event.user?.name ?: return
        val disconnectedUserId = markSessionDisconnected(playerId, event.sessionId()) ?: return
        authService?.markUserDisconnected(disconnectedUserId)
        val disconnectedGameState = gameService?.markPlayerDisconnected(playerId)
        if (disconnectedGameState != null) {
            messagingTemplate.convertAndSend(disconnectedGameState.topicPath(), disconnectedGameState)
        }
        if (lobbyService.isPlayerInLobby(playerId)) {
            val updatedState = lobbyService.leave(playerId)
            logger.info("Player disconnected and removed from lobby: $playerId")
            messagingTemplate.convertAndSend("/topic/lobby", updatedState.toUpdateMessage())
        }
        val authenticatedLobby = runCatching { lobbyService.getCurrentLobbyForUser(playerId) }.getOrNull()
        if (authenticatedLobby?.lobbyId != null) {
            val updatedLobby = lobbyService.leaveLobby(playerId, authenticatedLobby.lobbyId)
            logger.info("Authenticated player disconnected and removed from lobby: $playerId")
            updatedLobby.joinCode?.let { code ->
                messagingTemplate.convertAndSend("/topic/lobbies/$code", updatedLobby.toUpdateMessage())
            }
        }
    }

    @Scheduled(fixedDelay = WEBSOCKET_PRESENCE_REFRESH_MS)
    fun refreshActiveWebSocketPresence() {
        activeWebSocketSessionsByUser.keys.forEach { userId ->
            if ((activeWebSocketSessionsByUser[userId] ?: 0) > 0) {
                refreshPresence(userId)
            }
        }
    }

    private fun refreshPresence(userId: String) {
        val currentLobbyId = runCatching { lobbyService.getCurrentLobbyForUser(userId)?.lobbyId }.getOrNull()
        authService?.markUserConnected(userId, currentLobbyId)
    }

    private fun markSessionConnected(userId: String, sessionId: String?) {
        if (sessionId != null && activeWebSocketUsersBySessionId.putIfAbsent(sessionId, userId) != null) {
            return
        }
        activeWebSocketSessionsByUser.compute(userId) { _, current -> (current ?: 0) + 1 }
    }

    private fun markSessionDisconnected(userId: String, sessionId: String?): String? {
        val countedUserId = if (sessionId == null) {
            userId
        } else {
            activeWebSocketUsersBySessionId.remove(sessionId) ?: return null
        }

        var shouldMarkDisconnected = false
        activeWebSocketSessionsByUser.compute(countedUserId) { _, current ->
            val next = (current ?: 1) - 1
            if (next > 0) {
                next
            } else {
                shouldMarkDisconnected = true
                null
            }
        }
        return if (shouldMarkDisconnected) countedUserId else null
    }

    private fun SessionConnectedEvent.sessionId(): String? =
        runCatching { SimpMessageHeaderAccessor.getSessionId(message.headers) }.getOrNull()

    private fun SessionDisconnectEvent.sessionId(): String? =
        runCatching { sessionId }.getOrNull()

    private companion object {
        const val WEBSOCKET_PRESENCE_REFRESH_MS = 20_000L
    }
}

private fun LobbyState.toUpdateMessage() = LobbyUpdateMessage(
    lobbyId = lobbyId,
    joinCode = joinCode,
    players = players.map { LobbyPlayerInfo(nickname = it.nickname, isHost = it.isHost) },
    status = status,
    maxPlayers = maxPlayers,
)

private fun GameUpdateMessage.topicPath(): String =
    gameId?.let { "/topic/games/$it" } ?: "/topic/game"
