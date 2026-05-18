package at.aau.se2.skyjo.service

import at.aau.se2.skyjo.model.auth.AuthResponse
import at.aau.se2.skyjo.model.auth.AuthUserDto
import at.aau.se2.skyjo.model.auth.WsTicketResponse
import at.aau.se2.skyjo.persistence.AuthRepository
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.dao.DataAccessException
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder
import org.springframework.security.crypto.password.PasswordEncoder
import org.springframework.stereotype.Service
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import java.util.UUID

interface SecureTokenGenerator {
    fun generateToken(): String
}

class RandomSecureTokenGenerator : SecureTokenGenerator {
    private val secureRandom = SecureRandom()

    override fun generateToken(): String {
        val bytes = ByteArray(32)
        secureRandom.nextBytes(bytes)
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
    }
}

class InvalidAuthInputException(message: String) : IllegalArgumentException(message)

class DuplicateUsernameException : IllegalStateException("Username is already taken")

class InvalidCredentialsException : IllegalArgumentException("Invalid username or password")

class UnauthorizedException : IllegalArgumentException("Authentication required")

@Service
class AuthService @Autowired constructor(
    private val repository: AuthRepository,
) {

    private var passwordEncoder: PasswordEncoder = BCryptPasswordEncoder()
    private var nowProvider: () -> Long = { System.currentTimeMillis() }
    private var tokenGenerator: SecureTokenGenerator = RandomSecureTokenGenerator()

    internal constructor(
        repository: AuthRepository,
        passwordEncoder: PasswordEncoder,
        nowProvider: () -> Long,
        tokenGenerator: SecureTokenGenerator,
    ) : this(repository) {
        this.passwordEncoder = passwordEncoder
        this.nowProvider = nowProvider
        this.tokenGenerator = tokenGenerator
    }

    fun register(username: String, password: String): AuthResponse {
        val normalizedUsername = validateUsername(username)
        validatePassword(password)

        if (repository.findUserByUsername(normalizedUsername) != null) {
            throw DuplicateUsernameException()
        }

        val now = nowProvider()
        val userId = UUID.randomUUID().toString()
        val passwordHash = passwordEncoder.encode(password)

        try {
            repository.createUser(userId, normalizedUsername, passwordHash, now)
        } catch (e: DataAccessException) {
            if (repository.findUserByUsername(normalizedUsername) != null) {
                throw DuplicateUsernameException()
            }
            throw e
        }

        return createAuthResponse(userId, normalizedUsername, now)
    }

    fun login(username: String, password: String): AuthResponse {
        val user = repository.findUserByUsername(username.trim())
            ?: throw InvalidCredentialsException()

        if (!passwordEncoder.matches(password, user.passwordHash)) {
            throw InvalidCredentialsException()
        }

        return createAuthResponse(user.userId, user.username, nowProvider())
    }

    fun requireUser(token: String): AuthUserDto {
        val now = nowProvider()
        val session = repository.findActiveSession(hashSecret(token), now)
            ?: throw UnauthorizedException()
        repository.touchSession(session.tokenHash, now)
        val user = repository.findUserById(session.userId)
            ?: throw UnauthorizedException()
        return AuthUserDto(userId = user.userId, username = user.username)
    }

    fun logout(token: String) {
        repository.revokeSession(hashSecret(token), nowProvider())
    }

    fun createWebSocketTicket(token: String): WsTicketResponse {
        val user = requireUser(token)
        val now = nowProvider()
        val ticket = tokenGenerator.generateToken()
        val expiresAt = now + WEBSOCKET_TICKET_TTL_MILLIS
        repository.createWebSocketTicket(
            ticketHash = hashSecret(ticket),
            userId = user.userId,
            createdAt = now,
            expiresAt = expiresAt,
        )
        return WsTicketResponse(ticket = ticket, expiresAt = expiresAt)
    }

    fun consumeWebSocketTicket(ticket: String): AuthUserDto? {
        val consumed = repository.consumeWebSocketTicket(hashSecret(ticket), nowProvider())
            ?: return null
        val user = repository.findUserById(consumed.userId) ?: return null
        return AuthUserDto(userId = user.userId, username = user.username)
    }

    fun markUserConnected(userId: String, currentLobbyId: String? = null) {
        repository.setPresence(
            userId = userId,
            connected = true,
            currentLobbyId = currentLobbyId,
            now = nowProvider(),
        )
    }

    fun markUserDisconnected(userId: String) {
        repository.setPresence(
            userId = userId,
            connected = false,
            currentLobbyId = null,
            now = nowProvider(),
        )
    }

    private fun createAuthResponse(userId: String, username: String, now: Long): AuthResponse {
        val token = tokenGenerator.generateToken()
        repository.createSession(
            tokenHash = hashSecret(token),
            userId = userId,
            createdAt = now,
            expiresAt = now + SESSION_TTL_MILLIS,
        )
        return AuthResponse(
            token = token,
            user = AuthUserDto(userId = userId, username = username),
        )
    }

    private fun validateUsername(username: String): String {
        val trimmed = username.trim()
        if (!USERNAME_PATTERN.matches(trimmed)) {
            throw InvalidAuthInputException("Username must be 3-20 characters and contain only letters, numbers, and underscores")
        }
        return trimmed
    }

    private fun validatePassword(password: String) {
        if (password.length < 8) {
            throw InvalidAuthInputException("Password must be at least 8 characters")
        }
    }

    private fun hashSecret(value: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8))
        return digest.joinToString(separator = "") { "%02x".format(it) }
    }

    companion object {
        const val SESSION_TTL_MILLIS: Long = 30L * 24L * 60L * 60L * 1_000L
        const val WEBSOCKET_TICKET_TTL_MILLIS: Long = 60L * 1_000L
        private val USERNAME_PATTERN = Regex("^[A-Za-z0-9_]{3,20}$")
    }
}
