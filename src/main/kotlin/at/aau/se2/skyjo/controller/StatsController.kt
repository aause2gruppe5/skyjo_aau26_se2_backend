package at.aau.se2.skyjo.controller

import at.aau.se2.skyjo.model.auth.ErrorResponse
import at.aau.se2.skyjo.service.StatsService
import at.aau.se2.skyjo.service.UnauthorizedException
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

@RestController
class StatsController(
    private val statsService: StatsService,
    private val authSupport: AuthSupport,
) {

    @GetMapping("/api/stats/me")
    fun myStats(
        @RequestHeader("Authorization") authorizationHeader: String?,
    ): ResponseEntity<Any> =
        runCatching {
            val user = authSupport.requireUser(authorizationHeader)
            ResponseEntity.ok(statsService.getStats(user.userId) as Any)
        }.getOrElse(::toStatsError)

    @GetMapping("/api/leaderboard")
    fun leaderboard(
        @RequestParam(required = false) limit: Int?,
    ): ResponseEntity<Any> =
        ResponseEntity.ok(statsService.leaderboard(limit ?: DEFAULT_LEADERBOARD_LIMIT) as Any)

    private companion object {
        const val DEFAULT_LEADERBOARD_LIMIT = 50
    }
}

private fun toStatsError(error: Throwable): ResponseEntity<Any> =
    when (error) {
        is UnauthorizedException -> ResponseEntity
            .status(HttpStatus.UNAUTHORIZED)
            .body(ErrorResponse(error.message ?: "Authentication required"))
        else -> ResponseEntity.badRequest().body(ErrorResponse(error.message ?: "invalid stats operation"))
    }
