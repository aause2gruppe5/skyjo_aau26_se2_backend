package at.aau.se2.skyjo.service

import at.aau.se2.skyjo.persistence.AuthRepository
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.datasource.SingleConnectionDataSource
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder

class AuthServiceTest {

    private lateinit var repo: AuthRepository
    private lateinit var service: AuthService
    private var now = 1_000L

    @BeforeEach
    fun setUp() {
        val dataSource = SingleConnectionDataSource("jdbc:sqlite::memory:", true)
        repo = AuthRepository(JdbcTemplate(dataSource))
        repo.initSchema()
        service = AuthService(
            repository = repo,
            passwordEncoder = BCryptPasswordEncoder(),
            nowProvider = { now },
            tokenGenerator = object : SecureTokenGenerator {
                private var next = 0
                override fun generateToken(): String {
                    next += 1
                    return "secure-token-value-$next-with-at-least-32-characters"
                }
            },
        )
    }

    @Test
    fun `register stores bcrypt hash and returns session token`() {
        val response = service.register(username = "Alice_1", password = "password123")

        val storedUser = repo.findUserByUsername("alice_1")
        assertNotNull(storedUser)
        assertEquals("Alice_1", storedUser?.username)
        assertNotEquals("password123", storedUser?.passwordHash)
        assertTrue(storedUser?.passwordHash.orEmpty().startsWith("$2"))
        assertEquals("secure-token-value-1-with-at-least-32-characters", response.token)
        assertEquals("Alice_1", response.user.username)
        assertNull(repo.findActiveSession(response.token, now))
    }

    @Test
    fun `register rejects invalid username and short password`() {
        assertThrows<InvalidAuthInputException> {
            service.register(username = "al", password = "password123")
        }
        assertThrows<InvalidAuthInputException> {
            service.register(username = "valid_name", password = "short")
        }
    }

    @Test
    fun `register rejects duplicate username case-insensitively`() {
        service.register(username = "Alice", password = "password123")

        assertThrows<DuplicateUsernameException> {
            service.register(username = "alice", password = "password123")
        }
    }

    @Test
    fun `login returns generic error for unknown user or wrong password`() {
        service.register(username = "Alice", password = "password123")

        val wrongPassword = assertThrows<InvalidCredentialsException> {
            service.login(username = "Alice", password = "wrong-password")
        }
        val unknownUser = assertThrows<InvalidCredentialsException> {
            service.login(username = "Unknown", password = "password123")
        }

        assertEquals("Invalid username or password", wrongPassword.message)
        assertEquals("Invalid username or password", unknownUser.message)
    }

    @Test
    fun `login returns new token for valid credentials`() {
        service.register(username = "Alice", password = "password123")

        val login = service.login(username = "alice", password = "password123")

        assertEquals("Alice", login.user.username)
        assertEquals("secure-token-value-2-with-at-least-32-characters", login.token)
    }

    @Test
    fun `requireUser resolves valid token and rejects revoked token`() {
        val registered = service.register(username = "Alice", password = "password123")

        val user = service.requireUser(registered.token)
        service.logout(registered.token)

        assertEquals("Alice", user.username)
        assertThrows<UnauthorizedException> {
            service.requireUser(registered.token)
        }
    }

    @Test
    fun `session expires after thirty days`() {
        val registered = service.register(username = "Alice", password = "password123")

        now += AuthService.SESSION_TTL_MILLIS + 1

        assertThrows<UnauthorizedException> {
            service.requireUser(registered.token)
        }
    }

    @Test
    fun `websocket ticket is short lived and consumed once`() {
        val registered = service.register(username = "Alice", password = "password123")

        val ticket = service.createWebSocketTicket(registered.token)
        val consumed = service.consumeWebSocketTicket(ticket.ticket)
        val consumedAgain = service.consumeWebSocketTicket(ticket.ticket)

        assertEquals(now + AuthService.WEBSOCKET_TICKET_TTL_MILLIS, ticket.expiresAt)
        assertEquals("Alice", consumed?.username)
        assertNull(consumedAgain)
    }

    @Test
    fun `raw websocket ticket is not stored`() {
        val registered = service.register(username = "Alice", password = "password123")

        val ticket = service.createWebSocketTicket(registered.token)

        assertNull(repo.consumeWebSocketTicket(ticket.ticket, now))
    }

    @Test
    fun `generated default tokens are url safe and long enough`() {
        val generated = RandomSecureTokenGenerator().generateToken()

        assertTrue(generated.length >= 43)
        assertFalse(generated.contains("+"))
        assertFalse(generated.contains("/"))
        assertFalse(generated.contains("="))
    }
}
