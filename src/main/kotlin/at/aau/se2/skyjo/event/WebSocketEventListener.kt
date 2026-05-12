package at.aau.se2.skyjo.event

import at.aau.se2.skyjo.model.MessageType
import at.aau.se2.skyjo.model.ServerMessage
import at.aau.se2.skyjo.persistence.GameRepository
import at.aau.se2.skyjo.service.ConnectionService
import org.slf4j.LoggerFactory
import org.springframework.context.event.EventListener
import org.springframework.messaging.simp.SimpMessageSendingOperations
import org.springframework.stereotype.Component
import org.springframework.web.socket.messaging.SessionConnectedEvent
import org.springframework.web.socket.messaging.SessionDisconnectEvent

@Component
class WebSocketEventListener(
    private val messagingTemplate: SimpMessageSendingOperations,
    private val connectionService: ConnectionService,
    private val gameRepository: GameRepository?,
) {

    private val logger = LoggerFactory.getLogger(WebSocketEventListener::class.java)

    @EventListener
    fun handleWebSocketConnectListener(event: SessionConnectedEvent) {
        val sessionId = event.message.headers["simpSessionId"]
        logger.info("New WebSocket connection established: sessionId=$sessionId")
    }

    @EventListener
    fun handleWebSocketDisconnectListener(event: SessionDisconnectEvent) {
        val sessionId = event.sessionId
        val playerName = connectionService.removeSession(sessionId)
        if (playerName != null) {
            logger.info("Player disconnected: $playerName (sessionId=$sessionId)")
            gameRepository?.markDisconnected(playerName)
            messagingTemplate.convertAndSend(
                "/topic/public",
                ServerMessage(MessageType.PLAYER_LEFT, "$playerName has left.", playerName)
            )
        }
    }
}
