package at.aau.se2.skyjo.controller

import at.aau.se2.skyjo.model.GameConfig
import at.aau.se2.skyjo.model.GameUpdateMessage
import at.aau.se2.skyjo.model.LobbyPlayerInfo
import at.aau.se2.skyjo.model.LobbyUpdateMessage
import at.aau.se2.skyjo.model.PlayerMessage
import at.aau.se2.skyjo.model.StartGameMessage
import at.aau.se2.skyjo.model.lobby.LobbyState
import at.aau.se2.skyjo.model.auth.ErrorResponse
import at.aau.se2.skyjo.model.lobby.LobbySummaryResponse
import at.aau.se2.skyjo.persistence.GameRepository
import at.aau.se2.skyjo.service.GameService
import at.aau.se2.skyjo.service.LobbyService
import at.aau.se2.skyjo.service.UnauthorizedException
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.slf4j.LoggerFactory
import org.springframework.messaging.handler.annotation.MessageMapping
import org.springframework.messaging.handler.annotation.Payload
import org.springframework.messaging.simp.SimpMessageHeaderAccessor
import org.springframework.messaging.simp.SimpMessageSendingOperations
import org.springframework.stereotype.Controller
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestHeader

@Controller
class LobbyController(
    private val lobbyService: LobbyService,
    private val gameService: GameService,
    private val messagingTemplate: SimpMessageSendingOperations,
    private val gameRepository: GameRepository?,
    private val authSupport: AuthSupport? = null,
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

            // Uniqueness: Prüfen, ob der Name in der Lobby schon existiert
            val currentState = lobbyService.getState()
            if (currentState.players.any { it.nickname.equals(rawName, ignoreCase = true) }) {
                error("Nickname '$rawName' is already in use")
            }

            // Wenn wir hier ankommen, ist der Name gültig und einzigartig!
            val nickname = rawName

            // Spieler der Lobby hinzufügen
            val state = lobbyService.join(playerId, nickname)
            logger.info("$nickname ($playerId) joined lobby")

            // Alle Clients über das Update informieren
            messagingTemplate.convertAndSend("/topic/lobby", state.toUpdateMessage())
            // Direkt an beitretenden Spieler senden (Subscription-Race-Condition vermeiden)
            messagingTemplate.convertAndSendToUser(playerId, "/queue/lobby", state.toUpdateMessage())

        }.onFailure { e ->
            // Fehler (z.B. Name zu kurz oder vergeben) an den jeweiligen Spieler zurücksenden
            messagingTemplate.convertAndSendToUser(playerId, "/queue/errors", mapOf("message" to e.message))
        }
    }

    @PostMapping("/api/lobbies")
    fun createLobby(
        @RequestHeader("Authorization") authorizationHeader: String?,
    ): ResponseEntity<Any> =
        runCatching {
            val user = requireAuthSupport().requireUser(authorizationHeader)
            val lobby = lobbyService.createLobby(user)
            messagingTemplate.convertAndSend("/topic/lobbies/${lobby.joinCode}", lobby.toUpdateMessage())
            ResponseEntity.status(HttpStatus.CREATED).body(lobby.toSummaryResponse() as Any)
        }.getOrElse(::toRestError)

    @PostMapping("/api/lobbies/{joinCode}/join")
    fun joinLobbyByCode(
        @PathVariable joinCode: String,
        @RequestHeader("Authorization") authorizationHeader: String?,
    ): ResponseEntity<Any> =
        runCatching {
            val user = requireAuthSupport().requireUser(authorizationHeader)
            val lobby = lobbyService.joinLobby(user, joinCode)
            messagingTemplate.convertAndSend("/topic/lobbies/${lobby.joinCode}", lobby.toUpdateMessage())
            ResponseEntity.ok(lobby.toSummaryResponse() as Any)
        }.getOrElse(::toRestError)

    @PostMapping("/api/lobbies/{lobbyId}/leave")
    fun leaveLobbyById(
        @PathVariable lobbyId: String,
        @RequestHeader("Authorization") authorizationHeader: String?,
    ): ResponseEntity<Any> =
        runCatching {
            val user = requireAuthSupport().requireUser(authorizationHeader)
            val lobby = lobbyService.leaveLobby(user.userId, lobbyId)
            lobby.joinCode?.let { code ->
                messagingTemplate.convertAndSend("/topic/lobbies/$code", lobby.toUpdateMessage())
            }
            ResponseEntity.ok(lobby.toSummaryResponse() as Any)
        }.getOrElse(::toRestError)

    @GetMapping("/api/lobbies/current")
    fun currentLobby(
        @RequestHeader("Authorization") authorizationHeader: String?,
    ): ResponseEntity<Any> =
        runCatching {
            val user = requireAuthSupport().requireUser(authorizationHeader)
            val lobby = lobbyService.getCurrentLobbyForUser(user.userId)
                ?: return ResponseEntity.noContent().build<Any>()
            ResponseEntity.ok(lobby.toSummaryResponse() as Any)
        }.getOrElse(::toRestError)

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
            val currentLobby = runCatching { lobbyService.getCurrentLobbyForUser(playerId) }.getOrNull()
            val lobbyState = currentLobby?.lobbyId
                ?.let { lobbyId -> lobbyService.startGame(userId = playerId, lobbyId = lobbyId) }
                ?: lobbyService.startGame(playerId)
            val gameConfig = message?.let { GameConfig(maxRounds = it.maxRounds, targetScore = it.targetScore) }
                ?: GameConfig()
            logger.info("Game started by host $playerId (maxRounds=${gameConfig.maxRounds}, targetScore=${gameConfig.targetScore})")
            messagingTemplate.convertAndSend(lobbyState.topicPath(), lobbyState.toUpdateMessage())
            val gameState = lobbyState.lobbyId
                ?.let { lobbyId -> gameService.startGame(lobbyId, lobbyState.players, gameConfig) }
                ?: gameService.startGame(lobbyState.players, gameConfig)
            messagingTemplate.convertAndSend(gameState.topicPath(), gameState)
            lobbyState.players.forEach { player ->
                messagingTemplate.convertAndSendToUser(player.userId, "/queue/gamestate", gameState)
            }
        }.onFailure { e ->
            messagingTemplate.convertAndSendToUser(playerId, "/queue/errors", mapOf("message" to e.message))
        }
    }

    private fun requireAuthSupport(): AuthSupport =
        authSupport ?: throw UnauthorizedException()
}

private fun LobbyState.toUpdateMessage() = LobbyUpdateMessage(
    lobbyId = lobbyId,
    joinCode = joinCode,
    players = players.map { LobbyPlayerInfo(nickname = it.nickname, isHost = it.isHost) },
    status = status,
    maxPlayers = maxPlayers,
)

private fun LobbyState.topicPath(): String =
    joinCode?.let { "/topic/lobbies/$it" } ?: "/topic/lobby"

private fun GameUpdateMessage.topicPath(): String =
    gameId?.let { "/topic/games/$it" } ?: "/topic/game"

private fun LobbyState.toSummaryResponse() = LobbySummaryResponse(
    lobbyId = requireNotNull(lobbyId) { "lobby id is missing" },
    joinCode = requireNotNull(joinCode) { "join code is missing" },
    players = players.map { LobbyPlayerInfo(nickname = it.nickname, isHost = it.isHost) },
    status = status,
    maxPlayers = maxPlayers,
)

private fun toRestError(error: Throwable): ResponseEntity<Any> =
    when (error) {
        is UnauthorizedException -> ResponseEntity
            .status(HttpStatus.UNAUTHORIZED)
            .body(ErrorResponse(error.message ?: "Authentication required"))
        is IllegalStateException ->
            if (error.message?.contains("not found", ignoreCase = true) == true) {
                ResponseEntity.status(HttpStatus.NOT_FOUND).body(ErrorResponse(error.message ?: "lobby not found"))
            } else {
                ResponseEntity.badRequest().body(ErrorResponse(error.message ?: "invalid lobby operation"))
            }
        else -> ResponseEntity.badRequest().body(ErrorResponse(error.message ?: "invalid lobby operation"))
    }
