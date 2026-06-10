package at.aau.se2.skyjo.controller

import at.aau.se2.skyjo.game.model.PlayActionCardCommand
import at.aau.se2.skyjo.model.GameActionMessage
import at.aau.se2.skyjo.service.GameService
import org.slf4j.LoggerFactory
import org.springframework.messaging.handler.annotation.MessageMapping
import org.springframework.messaging.handler.annotation.Payload
import org.springframework.messaging.simp.SimpMessageHeaderAccessor
import org.springframework.messaging.simp.SimpMessageSendingOperations
import org.springframework.stereotype.Controller

@Controller
class GameController(
    private val gameService: GameService,
    private val messagingTemplate: SimpMessageSendingOperations,
) {

    private val logger = LoggerFactory.getLogger(GameController::class.java)

    @MessageMapping("/game.action")
    fun gameAction(
        @Payload action: GameActionMessage,
        headerAccessor: SimpMessageHeaderAccessor,
    ) {
        val playerId = headerAccessor.user?.name ?: return
        runCatching {
            val updatedState = gameService.processAction(playerId, action)
            logger.info("Game action ${action.type} by $playerId")
            messagingTemplate.convertAndSend(updatedState.topicPath(), updatedState)
        }.onFailure { e ->
            messagingTemplate.convertAndSendToUser(playerId, "/queue/errors", mapOf("message" to e.message))
        }
    }

    @MessageMapping("/game.cheat-peek")
    fun cheatPeekDrawPile(headerAccessor: SimpMessageHeaderAccessor) {
        val playerId = headerAccessor.user?.name ?: return
        runCatching {
            val result = gameService.cheatPeekDrawPile(playerId)
            logger.info("Cheat peek draw pile by $playerId")
            messagingTemplate.convertAndSendToUser(playerId, "/queue/cheat-peek-results", result)
        }.onFailure { e ->
            messagingTemplate.convertAndSendToUser(playerId, "/queue/errors", mapOf("message" to e.message))
        }
    }

    @MessageMapping("/game.cheat-report")
    fun cheatReportCurrentPlayer(headerAccessor: SimpMessageHeaderAccessor) {
        val playerId = headerAccessor.user?.name ?: return
        runCatching {
            val result = gameService.cheatReportCurrentPlayer(playerId)
            logger.info("Cheat report by $playerId")
            messagingTemplate.convertAndSend(result.gameUpdate.topicPath(), result.gameUpdate)
            messagingTemplate.convertAndSendToUser(playerId, "/queue/cheat-report-results", result.privateReportResult)
        }.onFailure { e ->
            messagingTemplate.convertAndSendToUser(playerId, "/queue/errors", mapOf("message" to e.message))
        }
    }

    @MessageMapping("/game.action-card")
    fun playActionCard(
        @Payload command: PlayActionCardCommand,
        headerAccessor: SimpMessageHeaderAccessor,
    ) {
        val playerId = headerAccessor.user?.name ?: return
        runCatching {
            val result = gameService.playActionCard(playerId, command)
            logger.info("Action card ${command.actionCardIndex} played by $playerId")
            messagingTemplate.convertAndSend(result.gameUpdate.topicPath(), result.gameUpdate)
            result.privateActionCardResults.forEach { (recipientPlayerId, actionCardResult) ->
                messagingTemplate.convertAndSendToUser(
                    recipientPlayerId,
                    "/queue/action-card-results",
                    actionCardResult,
                )
            }
        }.onFailure { e ->
            messagingTemplate.convertAndSendToUser(playerId, "/queue/errors", mapOf("message" to e.message))
        }
    }

    private fun at.aau.se2.skyjo.model.GameUpdateMessage.topicPath(): String =
        gameId?.let { "/topic/games/$it" } ?: "/topic/game"
}
