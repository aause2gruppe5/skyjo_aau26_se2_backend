package at.aau.se2.skyjo.controller

import at.aau.se2.skyjo.model.LobbyPlayerInfo
import at.aau.se2.skyjo.model.LobbyUpdateMessage
import at.aau.se2.skyjo.model.PlayerMessage
import at.aau.se2.skyjo.model.lobby.LobbyState
import at.aau.se2.skyjo.service.GameService
import at.aau.se2.skyjo.service.LobbyService
import org.slf4j.LoggerFactory
import org.springframework.messaging.handler.annotation.MessageMapping
import org.springframework.messaging.handler.annotation.Payload
import org.springframework.messaging.simp.SimpMessageHeaderAccessor
import org.springframework.messaging.simp.SimpMessageSendingOperations
import org.springframework.stereotype.Controller

@Controller
class LobbyController(
    private val lobbyService: LobbyService,
    private val gameService: GameService,
    private val messagingTemplate: SimpMessageSendingOperations,
) {

    private val logger = LoggerFactory.getLogger(LobbyController::class.java)

    @MessageMapping("/lobby.join")
    fun joinLobby(
        @Payload message: PlayerMessage,
        headerAccessor: SimpMessageHeaderAccessor,
    ) {
        val playerId = headerAccessor.user?.name ?: return
        runCatching {
            // TODO: nickname validation/uniqueness is a placeholder
            val nickname = message.playerName.ifBlank { "Player" }
            val state = lobbyService.join(playerId, nickname)
            logger.info("$nickname ($playerId) joined lobby")
            messagingTemplate.convertAndSend("/topic/lobby", state.toUpdateMessage())
        }.onFailure { e ->
            messagingTemplate.convertAndSendToUser(playerId, "/queue/errors", mapOf("message" to e.message))
        }
    }

    @MessageMapping("/lobby.leave")
    fun leaveLobby(headerAccessor: SimpMessageHeaderAccessor) {
        val playerId = headerAccessor.user?.name ?: return
        val state = lobbyService.leave(playerId)
        logger.info("$playerId left lobby")
        messagingTemplate.convertAndSend("/topic/lobby", state.toUpdateMessage())
    }

    // TODO: game start logic (initial reveals, round progression) is a placeholder
    @MessageMapping("/game.start")
    fun startGame(headerAccessor: SimpMessageHeaderAccessor) {
        val playerId = headerAccessor.user?.name ?: return
        runCatching {
            val lobbyState = lobbyService.startGame(playerId)
            logger.info("Game started by host $playerId")
            messagingTemplate.convertAndSend("/topic/lobby", lobbyState.toUpdateMessage())
            val gameState = gameService.startGame(lobbyState.players)
            messagingTemplate.convertAndSend("/topic/game", gameState)
        }.onFailure { e ->
            messagingTemplate.convertAndSendToUser(playerId, "/queue/errors", mapOf("message" to e.message))
        }
    }
}

private fun LobbyState.toUpdateMessage() = LobbyUpdateMessage(
    players = players.map { LobbyPlayerInfo(nickname = it.nickname, isHost = it.isHost) },
    status = status,
    maxPlayers = maxPlayers,
)
