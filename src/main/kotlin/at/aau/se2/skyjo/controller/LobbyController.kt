package at.aau.se2.skyjo.controller

import at.aau.se2.skyjo.model.GameConfig
import at.aau.se2.skyjo.model.LobbyPlayerInfo
import at.aau.se2.skyjo.model.LobbyUpdateMessage
import at.aau.se2.skyjo.model.PlayerMessage
import at.aau.se2.skyjo.model.StartGameMessage
import at.aau.se2.skyjo.model.lobby.LobbyState
import at.aau.se2.skyjo.persistence.GameRepository
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
    private val gameRepository: GameRepository?,
) {

    private val logger = LoggerFactory.getLogger(LobbyController::class.java)

    @MessageMapping("/lobby.join")
    fun joinLobby(
        @Payload message: PlayerMessage,
        headerAccessor: SimpMessageHeaderAccessor,
    ) {
        val playerId = headerAccessor.user?.name ?: return

        // Rejoin: Spieler hat eine aktive Game-Session → Spiel wiederherstellen
        val storedGameId = gameRepository?.getPlayerGame(message.playerName)
        if (storedGameId != null && storedGameId == gameService.getActiveGameId()) {
            gameRepository?.savePlayerSession(message.playerName, storedGameId, connected = true)
            gameService.addSessionAlias(playerId, message.playerName)
            val state = gameService.getCurrentState()
            if (state != null) {
                messagingTemplate.convertAndSendToUser(playerId, "/queue/gamestate", state)
            }
            logger.info("Player rejoined: ${message.playerName} (newSessionId=$playerId, gameId=$storedGameId)")
            return
        }

        runCatching {
            // Den Namen aus der App holen und Leerzeichen am Rand entfernen
            val rawName = message.playerName.trim()

            if (rawName.length !in 1..15) {
                error("Name has to be between 1 and 15 characters.")
            }

            // Uniqueness is enforced atomically inside lobbyService.join under its
            // lock, so two concurrent joins with the same name cannot both pass.
            val nickname = rawName

            // Spieler der Lobby hinzufügen
            val state = lobbyService.join(playerId, nickname)
            logger.info("$nickname ($playerId) joined lobby")

            // Alle Clients über das Update informieren
            messagingTemplate.convertAndSend("/topic/lobby", state.toUpdateMessage())
            // Direkt an beitretenden Spieler senden (Subscription-Race-Condition vermeiden)
            messagingTemplate.convertAndSendToUser(playerId, "/queue/lobby", state.toUpdateMessage())

        }.onFailure { e ->
            logger.warn("lobby.join failed for $playerId", e)
            messagingTemplate.convertAndSendToUser(
                playerId,
                "/queue/errors",
                mapOf("message" to (e.message ?: "Could not join lobby")),
            )
        }
    }

    @MessageMapping("/lobby.leave")
    fun leaveLobby(headerAccessor: SimpMessageHeaderAccessor) {
        val playerId = headerAccessor.user?.name ?: return
        val state = lobbyService.leave(playerId)
        logger.info("$playerId left lobby")
        messagingTemplate.convertAndSend("/topic/lobby", state.toUpdateMessage())
    }

    @MessageMapping("/game.start")
    fun startGame(
        @Payload(required = false) message: StartGameMessage?,
        headerAccessor: SimpMessageHeaderAccessor,
    ) {
        val playerId = headerAccessor.user?.name ?: return
        runCatching {
            val lobbyState = lobbyService.startGame(playerId)
            val gameConfig = message?.let { GameConfig(maxRounds = it.maxRounds, targetScore = it.targetScore) }
                ?: GameConfig()
            logger.info("Game started by host $playerId (maxRounds=${gameConfig.maxRounds}, targetScore=${gameConfig.targetScore})")
            messagingTemplate.convertAndSend("/topic/lobby", lobbyState.toUpdateMessage())
            val gameState = gameService.startGame(lobbyState.players, gameConfig)
            messagingTemplate.convertAndSend("/topic/game", gameState)
        }.onFailure { e ->
            logger.warn("game.start failed for $playerId", e)
            messagingTemplate.convertAndSendToUser(
                playerId,
                "/queue/errors",
                mapOf("message" to (e.message ?: "Could not start game")),
            )
        }
    }
}

private fun LobbyState.toUpdateMessage() = LobbyUpdateMessage(
    players = players.map { LobbyPlayerInfo(nickname = it.nickname, isHost = it.isHost) },
    status = status,
    maxPlayers = maxPlayers,
)
