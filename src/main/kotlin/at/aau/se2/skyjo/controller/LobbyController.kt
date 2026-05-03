package at.aau.se2.skyjo.controller

import at.aau.se2.skyjo.model.GameConfig
import at.aau.se2.skyjo.model.LobbyPlayerInfo
import at.aau.se2.skyjo.model.LobbyUpdateMessage
import at.aau.se2.skyjo.model.PlayerMessage
import at.aau.se2.skyjo.model.StartGameMessage
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
            // Den Namen aus der App holen und Leerzeichen am Rand entfernen
            val rawName = message.playerName.trim()

            // 1. Validierung: Länge prüfen
            if (rawName.length !in 3..15) {
                error("Der Nickname muss zwischen 3 und 15 Zeichen lang sein.")
            }

            // 2. Uniqueness: Prüfen, ob der Name in der Lobby schon existiert
            val currentState = lobbyService.getState()
            if (currentState.players.any { it.nickname.equals(rawName, ignoreCase = true) }) {
                error("Der Nickname '$rawName' ist bereits vergeben.")
            }

            // Wenn wir hier ankommen, ist der Name gültig und einzigartig!
            val nickname = rawName

            // Spieler der Lobby hinzufügen
            val state = lobbyService.join(playerId, nickname)
            logger.info("$nickname ($playerId) joined lobby")

            // Alle Clients über das Update informieren
            messagingTemplate.convertAndSend("/topic/lobby", state.toUpdateMessage())

        }.onFailure { e ->
            // Fehler (z.B. Name zu kurz oder vergeben) an den jeweiligen Spieler zurücksenden
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
            messagingTemplate.convertAndSendToUser(playerId, "/queue/errors", mapOf("message" to e.message))
        }
    }
}

private fun LobbyState.toUpdateMessage() = LobbyUpdateMessage(
    players = players.map { LobbyPlayerInfo(nickname = it.nickname, isHost = it.isHost) },
    status = status,
    maxPlayers = maxPlayers,
)
