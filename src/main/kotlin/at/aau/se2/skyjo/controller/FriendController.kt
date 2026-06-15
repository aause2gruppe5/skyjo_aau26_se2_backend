package at.aau.se2.skyjo.controller

import at.aau.se2.skyjo.model.auth.ErrorResponse
import at.aau.se2.skyjo.model.social.SendFriendRequestRequest
import at.aau.se2.skyjo.service.FriendService
import at.aau.se2.skyjo.service.UnauthorizedException
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

@RestController
class FriendController(
    private val friendService: FriendService,
    private val authSupport: AuthSupport,
) {

    @GetMapping("/api/users/search")
    fun searchUsers(
        @RequestParam query: String,
        @RequestHeader("Authorization") authorizationHeader: String?,
    ): ResponseEntity<Any> =
        runCatching {
            val user = authSupport.requireUser(authorizationHeader)
            ResponseEntity.ok(friendService.searchUsers(user, query) as Any)
        }.getOrElse(::toSocialError)

    @GetMapping("/api/friends")
    fun listFriends(
        @RequestHeader("Authorization") authorizationHeader: String?,
    ): ResponseEntity<Any> =
        runCatching {
            val user = authSupport.requireUser(authorizationHeader)
            ResponseEntity.ok(friendService.listFriends(user) as Any)
        }.getOrElse(::toSocialError)

    @PostMapping("/api/social/heartbeat")
    fun heartbeat(
        @RequestHeader("Authorization") authorizationHeader: String?,
    ): ResponseEntity<Any> =
        runCatching {
            val user = authSupport.requireUser(authorizationHeader)
            friendService.recordHeartbeat(user)
            ResponseEntity.noContent().build<Any>()
        }.getOrElse(::toSocialError)

    @GetMapping("/api/friends/requests")
    fun listFriendRequests(
        @RequestHeader("Authorization") authorizationHeader: String?,
    ): ResponseEntity<Any> =
        runCatching {
            val user = authSupport.requireUser(authorizationHeader)
            ResponseEntity.ok(friendService.listFriendRequests(user) as Any)
        }.getOrElse(::toSocialError)

    @PostMapping("/api/friends/requests")
    fun sendFriendRequest(
        @RequestBody request: SendFriendRequestRequest,
        @RequestHeader("Authorization") authorizationHeader: String?,
    ): ResponseEntity<Any> =
        runCatching {
            val user = authSupport.requireUser(authorizationHeader)
            ResponseEntity.status(HttpStatus.CREATED).body(friendService.sendFriendRequest(user, request.toUserId) as Any)
        }.getOrElse(::toSocialError)

    @PostMapping("/api/friends/requests/{requestId}/accept")
    fun acceptFriendRequest(
        @PathVariable requestId: String,
        @RequestHeader("Authorization") authorizationHeader: String?,
    ): ResponseEntity<Any> =
        runCatching {
            val user = authSupport.requireUser(authorizationHeader)
            ResponseEntity.ok(friendService.acceptFriendRequest(user, requestId) as Any)
        }.getOrElse(::toSocialError)

    @PostMapping("/api/friends/requests/{requestId}/decline")
    fun declineFriendRequest(
        @PathVariable requestId: String,
        @RequestHeader("Authorization") authorizationHeader: String?,
    ): ResponseEntity<Any> =
        runCatching {
            val user = authSupport.requireUser(authorizationHeader)
            ResponseEntity.ok(friendService.declineFriendRequest(user, requestId) as Any)
        }.getOrElse(::toSocialError)
}

private fun toSocialError(error: Throwable): ResponseEntity<Any> =
    when (error) {
        is UnauthorizedException -> ResponseEntity
            .status(HttpStatus.UNAUTHORIZED)
            .body(ErrorResponse(error.message ?: "Authentication required"))
        is IllegalStateException ->
            if (error.message?.contains("not found", ignoreCase = true) == true) {
                ResponseEntity.status(HttpStatus.NOT_FOUND).body(ErrorResponse(error.message ?: "not found"))
            } else {
                ResponseEntity.badRequest().body(ErrorResponse(error.message ?: "invalid social operation"))
            }
        else -> ResponseEntity.badRequest().body(ErrorResponse(error.message ?: "invalid social operation"))
    }
