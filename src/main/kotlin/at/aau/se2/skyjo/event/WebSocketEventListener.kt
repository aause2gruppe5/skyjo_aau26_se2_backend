package at.aau.se2.skyjo.event

import at.aau.se2.skyjo.model.LobbyPlayerInfo
import at.aau.se2.skyjo.model.LobbyUpdateMessage
import at.aau.se2.skyjo.model.lobby.LobbyState
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
) {

    private val logger = LoggerFactory.getLogger(WebSocketEventListener::class.java)

    @EventListener
    fun handleWebSocketConnectListener(event: SessionConnectedEvent) {
        val sessionId = event.message.headers["simpSessionId"]
        logger.info("New WebSocket connection: sessionId=$sessionId")
    }

    @EventListener
    fun handleWebSocketDisconnectListener(event: SessionDisconnectEvent) {
        val sessionId = event.sessionId
        if (lobbyService.isPlayerInLobby(sessionId)) {
            val updatedState = lobbyService.leave(sessionId)
            logger.info("Player disconnected and removed from lobby: sessionId=$sessionId")
            messagingTemplate.convertAndSend("/topic/lobby", updatedState.toUpdateMessage())
        }
    }
}

private fun LobbyState.toUpdateMessage() = LobbyUpdateMessage(
    players = players.map { LobbyPlayerInfo(nickname = it.nickname, isHost = it.isHost) },
    status = status,
    maxPlayers = maxPlayers,
)
