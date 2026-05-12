package at.aau.se2.skyjo.controller

import at.aau.se2.skyjo.game.service.SkyjoGameService
import at.aau.se2.skyjo.model.MessageType
import at.aau.se2.skyjo.model.PlayerMessage
import at.aau.se2.skyjo.model.ServerMessage
import at.aau.se2.skyjo.persistence.GameRepository
import at.aau.se2.skyjo.service.ConnectionService
import org.slf4j.LoggerFactory
import org.springframework.messaging.handler.annotation.MessageMapping
import org.springframework.messaging.handler.annotation.Payload
import org.springframework.messaging.handler.annotation.SendTo
import org.springframework.messaging.simp.SimpMessageHeaderAccessor
import org.springframework.messaging.simp.SimpMessageSendingOperations
import org.springframework.stereotype.Controller

@Controller
class GameController(
    private val connectionService: ConnectionService,
    private val gameService: SkyjoGameService,
    private val gameRepository: GameRepository?,
    private val messagingTemplate: SimpMessageSendingOperations,
) {

    private val logger = LoggerFactory.getLogger(GameController::class.java)

    @MessageMapping("/game.join")
    @SendTo("/topic/public")
    fun joinGame(
        @Payload message: PlayerMessage,
        headerAccessor: SimpMessageHeaderAccessor
    ): ServerMessage {
        val sessionId = headerAccessor.sessionId
            ?: return ServerMessage(MessageType.ERROR, "Session not found")

        val incomingGameId = message.gameId
        val storedGameId = gameRepository?.getPlayerGame(message.playerName)

        if (incomingGameId != null && incomingGameId == storedGameId) {
            connectionService.registerSession(sessionId, message.playerName, storedGameId)
            gameRepository?.savePlayerSession(message.playerName, storedGameId, connected = true)
            val state = gameService.getGameState()
            if (state != null) {
                messagingTemplate.convertAndSendToUser(sessionId, "/queue/gamestate", state)
            }
            logger.info("Player rejoined: ${message.playerName} (sessionId=$sessionId, gameId=$storedGameId)")
            return ServerMessage(MessageType.PLAYER_REJOINED, "${message.playerName} rejoined.", message.playerName)
        }

        val activeGameId = gameService.getActiveGameId()
        connectionService.registerSession(sessionId, message.playerName, activeGameId)
        if (activeGameId != null) {
            gameRepository?.savePlayerSession(message.playerName, activeGameId, connected = true)
        }
        logger.info("Player joined: ${message.playerName} (sessionId=$sessionId)")
        return ServerMessage(MessageType.PLAYER_JOINED, "${message.playerName} joined.", message.playerName)
    }

    @MessageMapping("/game.leave")
    @SendTo("/topic/public")
    fun leaveGame(headerAccessor: SimpMessageHeaderAccessor): ServerMessage {
        val sessionId = headerAccessor.sessionId
            ?: return ServerMessage(MessageType.ERROR, "Session not found")
        val playerName = connectionService.removeSession(sessionId)
        logger.info("Player left: $playerName (sessionId=$sessionId)")
        return ServerMessage(MessageType.PLAYER_LEFT, "${playerName ?: "Unknown"} left.", playerName)
    }
}
