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
import org.springframework.stereotype.Component
import org.springframework.web.socket.messaging.SessionConnectedEvent
import org.springframework.web.socket.messaging.SessionDisconnectEvent

@Component
class WebSocketEventListener(
    private val messagingTemplate: SimpMessageSendingOperations,
    private val lobbyService: LobbyService,
    private val gameService: GameService?,
    private val authService: AuthService? = null,
) {

    private val logger = LoggerFactory.getLogger(WebSocketEventListener::class.java)

    @EventListener
    fun handleWebSocketConnectListener(event: SessionConnectedEvent) {
        val userId = event.user?.name
        logger.info("New WebSocket connection: principal=$userId")
        if (userId != null) {
            val currentLobbyId = runCatching { lobbyService.getCurrentLobbyForUser(userId)?.lobbyId }.getOrNull()
            authService?.markUserConnected(userId, currentLobbyId)
        }
    }

    @EventListener
    fun handleWebSocketDisconnectListener(event: SessionDisconnectEvent) {
        val playerId = event.user?.name ?: return
        authService?.markUserDisconnected(playerId)
        val disconnectedGameState = gameService?.markPlayerDisconnected(playerId)
        if (disconnectedGameState != null) {
            messagingTemplate.convertAndSend(disconnectedGameState.topicPath(), disconnectedGameState)
        }
        if (lobbyService.isPlayerInLobby(playerId)) {
            val updatedState = lobbyService.leave(playerId)
            logger.info("Player disconnected and removed from lobby: $playerId")
            messagingTemplate.convertAndSend("/topic/lobby", updatedState.toUpdateMessage())
        }
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
