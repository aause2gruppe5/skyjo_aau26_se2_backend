package at.aau.se2.skyjo.controller

import at.aau.se2.skyjo.model.LobbyPlayerInfo
import at.aau.se2.skyjo.model.LobbyUpdateMessage
import at.aau.se2.skyjo.model.auth.ErrorResponse
import at.aau.se2.skyjo.model.lobby.LobbyState
import at.aau.se2.skyjo.model.social.LobbyInviteRequest
import at.aau.se2.skyjo.service.LobbyInviteService
import at.aau.se2.skyjo.service.LobbyService
import at.aau.se2.skyjo.service.UnauthorizedException
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.messaging.simp.SimpMessageSendingOperations
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RestController

@RestController
class LobbyInviteController(
    private val inviteService: LobbyInviteService,
    private val lobbyService: LobbyService,
    private val authSupport: AuthSupport,
    private val messagingTemplate: SimpMessageSendingOperations,
) {

    @PostMapping("/api/lobbies/{lobbyId}/invites")
    fun createInvite(
        @PathVariable lobbyId: String,
        @RequestBody request: LobbyInviteRequest,
        @RequestHeader("Authorization") authorizationHeader: String?,
    ): ResponseEntity<Any> =
        runCatching {
            val user = authSupport.requireUser(authorizationHeader)
            val invite = inviteService.createInvite(user, lobbyId, request.toUserId)
            messagingTemplate.convertAndSendToUser(invite.to.userId, "/queue/invites", invite)
            ResponseEntity.status(HttpStatus.CREATED).body(invite as Any)
        }.getOrElse(::toInviteError)

    @GetMapping("/api/lobbies/invites")
    fun listInvites(
        @RequestHeader("Authorization") authorizationHeader: String?,
    ): ResponseEntity<Any> =
        runCatching {
            val user = authSupport.requireUser(authorizationHeader)
            ResponseEntity.ok(inviteService.listInvites(user) as Any)
        }.getOrElse(::toInviteError)

    @PostMapping("/api/lobbies/invites/{inviteId}/accept")
    fun acceptInvite(
        @PathVariable inviteId: String,
        @RequestHeader("Authorization") authorizationHeader: String?,
    ): ResponseEntity<Any> =
        runCatching {
            val user = authSupport.requireUser(authorizationHeader)
            val accepted = inviteService.acceptInvite(user, inviteId)
            lobbyService.getLobbyById(accepted.lobbyId)?.let { lobby ->
                lobby.joinCode?.let { code ->
                    messagingTemplate.convertAndSend("/topic/lobbies/$code", lobby.toUpdateMessage())
                }
            }
            ResponseEntity.ok(accepted as Any)
        }.getOrElse(::toInviteError)

    @PostMapping("/api/lobbies/invites/{inviteId}/decline")
    fun declineInvite(
        @PathVariable inviteId: String,
        @RequestHeader("Authorization") authorizationHeader: String?,
    ): ResponseEntity<Any> =
        runCatching {
            val user = authSupport.requireUser(authorizationHeader)
            ResponseEntity.ok(inviteService.declineInvite(user, inviteId) as Any)
        }.getOrElse(::toInviteError)
}

private fun LobbyState.toUpdateMessage() = LobbyUpdateMessage(
    lobbyId = lobbyId,
    joinCode = joinCode,
    players = players.map { LobbyPlayerInfo(nickname = it.nickname, isHost = it.isHost) },
    status = status,
    maxPlayers = maxPlayers,
)

private fun toInviteError(error: Throwable): ResponseEntity<Any> =
    when (error) {
        is UnauthorizedException -> ResponseEntity
            .status(HttpStatus.UNAUTHORIZED)
            .body(ErrorResponse(error.message ?: "Authentication required"))
        is IllegalStateException ->
            if (error.message?.contains("not found", ignoreCase = true) == true) {
                ResponseEntity.status(HttpStatus.NOT_FOUND).body(ErrorResponse(error.message ?: "not found"))
            } else {
                ResponseEntity.badRequest().body(ErrorResponse(error.message ?: "invalid invite operation"))
            }
        else -> ResponseEntity.badRequest().body(ErrorResponse(error.message ?: "invalid invite operation"))
    }
