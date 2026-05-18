package at.aau.se2.skyjo.controller

import at.aau.se2.skyjo.model.auth.AuthUserDto
import at.aau.se2.skyjo.service.AuthService
import at.aau.se2.skyjo.service.UnauthorizedException
import org.springframework.stereotype.Component

@Component
class AuthSupport(
    private val authService: AuthService,
) {

    fun extractBearerToken(authorizationHeader: String?): String? {
        val header = authorizationHeader?.trim() ?: return null
        if (!header.startsWith(BEARER_PREFIX, ignoreCase = true)) {
            return null
        }
        val token = header.substring(BEARER_PREFIX.length).trim()
        return token.ifEmpty { null }
    }

    fun requireUser(authorizationHeader: String?): AuthUserDto {
        val token = extractBearerToken(authorizationHeader) ?: throw UnauthorizedException()
        return authService.requireUser(token)
    }

    companion object {
        private const val BEARER_PREFIX = "Bearer "
    }
}
