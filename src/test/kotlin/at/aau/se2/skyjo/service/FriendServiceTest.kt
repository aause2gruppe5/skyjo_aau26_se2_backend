package at.aau.se2.skyjo.service

import at.aau.se2.skyjo.model.auth.AuthUserDto
import at.aau.se2.skyjo.model.social.FriendRequestStatus
import at.aau.se2.skyjo.model.social.RelationshipStatus
import at.aau.se2.skyjo.persistence.AuthRepository
import at.aau.se2.skyjo.persistence.FriendRepository
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.datasource.SingleConnectionDataSource

class FriendServiceTest {

    private lateinit var authRepository: AuthRepository
    private lateinit var repository: FriendRepository
    private lateinit var service: FriendService

    @BeforeEach
    fun setUp() {
        val dataSource = SingleConnectionDataSource("jdbc:sqlite::memory:", true)
        val jdbc = JdbcTemplate(dataSource)
        authRepository = AuthRepository(jdbc)
        repository = FriendRepository(jdbc)
        authRepository.initSchema()
        repository.initSchema()
        createUser("user-a", "Alice")
        createUser("user-b", "Bob")
        createUser("user-c", "Cara")
        service = FriendService(
            repository = repository,
            authRepository = authRepository,
            requestIdGenerator = object : FriendRequestIdGenerator {
                private var next = 0
                override fun generateId(): String {
                    next += 1
                    return "request-$next"
                }
            },
            nowProvider = { 1_000L },
        )
    }

    @Test
    fun `sendFriendRequest rejects self requests`() {
        val error = assertThrows<IllegalStateException> {
            service.sendFriendRequest(user("user-a", "Alice"), toUserId = "user-a")
        }

        assertTrue(error.message.orEmpty().contains("yourself"))
    }

    @Test
    fun `sendFriendRequest creates pending outgoing request`() {
        val request = service.sendFriendRequest(user("user-a", "Alice"), toUserId = "user-b")

        assertEquals("request-1", request.requestId)
        assertEquals(FriendRequestStatus.PENDING, request.status)
        assertEquals("Alice", request.from.username)
        assertEquals("Bob", request.to.username)
    }

    @Test
    fun `acceptFriendRequest creates reciprocal friendship`() {
        val request = service.sendFriendRequest(user("user-a", "Alice"), toUserId = "user-b")

        val accepted = service.acceptFriendRequest(user("user-b", "Bob"), request.requestId)

        assertEquals(FriendRequestStatus.ACCEPTED, accepted.status)
        assertEquals(listOf("Bob"), service.listFriends(user("user-a", "Alice")).map { it.username })
        assertEquals(listOf("Alice"), service.listFriends(user("user-b", "Bob")).map { it.username })
    }

    @Test
    fun `sendFriendRequest rejects duplicate friendship`() {
        val request = service.sendFriendRequest(user("user-a", "Alice"), toUserId = "user-b")
        service.acceptFriendRequest(user("user-b", "Bob"), request.requestId)

        val error = assertThrows<IllegalStateException> {
            service.sendFriendRequest(user("user-a", "Alice"), toUserId = "user-b")
        }

        assertTrue(error.message.orEmpty().contains("already friends"))
    }

    @Test
    fun `searchUsers returns relationship status`() {
        service.sendFriendRequest(user("user-a", "Alice"), toUserId = "user-b")

        val results = service.searchUsers(user("user-a", "Alice"), query = "b")

        assertEquals(RelationshipStatus.OUTGOING_REQUEST, results.single { it.username == "Bob" }.relationshipStatus)
    }

    private fun createUser(userId: String, username: String) {
        authRepository.createUser(
            userId = userId,
            username = username,
            passwordHash = "hash-$userId",
            now = 1L,
        )
    }

    private fun user(userId: String, username: String) = AuthUserDto(userId = userId, username = username)
}
