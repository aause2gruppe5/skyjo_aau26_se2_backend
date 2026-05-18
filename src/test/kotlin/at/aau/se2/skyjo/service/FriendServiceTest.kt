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
    fun `sendFriendRequest rejects unknown target users`() {
        val error = assertThrows<IllegalStateException> {
            service.sendFriendRequest(user("user-a", "Alice"), toUserId = "missing")
        }

        assertTrue(error.message.orEmpty().contains("user not found"))
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
    fun `sendFriendRequest rejects duplicate pending request in either direction`() {
        service.sendFriendRequest(user("user-a", "Alice"), toUserId = "user-b")

        val sameDirection = assertThrows<IllegalStateException> {
            service.sendFriendRequest(user("user-a", "Alice"), toUserId = "user-b")
        }
        val reverseDirection = assertThrows<IllegalStateException> {
            service.sendFriendRequest(user("user-b", "Bob"), toUserId = "user-a")
        }

        assertTrue(sameDirection.message.orEmpty().contains("already exists"))
        assertTrue(reverseDirection.message.orEmpty().contains("already exists"))
    }

    @Test
    fun `searchUsers returns relationship status`() {
        service.sendFriendRequest(user("user-a", "Alice"), toUserId = "user-b")

        val results = service.searchUsers(user("user-a", "Alice"), query = "b")

        assertEquals(RelationshipStatus.OUTGOING_REQUEST, results.single { it.username == "Bob" }.relationshipStatus)
    }

    @Test
    fun `searchUsers returns empty result for blank query`() {
        val results = service.searchUsers(user("user-a", "Alice"), query = "   ")

        assertTrue(results.isEmpty())
    }

    @Test
    fun `searchUsers reports incoming friend and no relationship statuses`() {
        service.sendFriendRequest(user("user-a", "Alice"), toUserId = "user-b")

        val incoming = service.searchUsers(user("user-b", "Bob"), query = "ali")
        val none = service.searchUsers(user("user-a", "Alice"), query = "cara")

        assertEquals(RelationshipStatus.INCOMING_REQUEST, incoming.single().relationshipStatus)
        assertEquals(RelationshipStatus.NONE, none.single().relationshipStatus)
    }

    @Test
    fun `searchUsers reports friends after accepted request`() {
        val request = service.sendFriendRequest(user("user-a", "Alice"), toUserId = "user-b")
        service.acceptFriendRequest(user("user-b", "Bob"), request.requestId)

        val results = service.searchUsers(user("user-a", "Alice"), query = "bob")

        assertEquals(RelationshipStatus.FRIENDS, results.single().relationshipStatus)
    }

    @Test
    fun `listFriendRequests separates incoming and outgoing requests`() {
        service.sendFriendRequest(user("user-a", "Alice"), toUserId = "user-b")
        service.sendFriendRequest(user("user-c", "Cara"), toUserId = "user-a")

        val requests = service.listFriendRequests(user("user-a", "Alice"))

        assertEquals(listOf("Cara"), requests.incoming.map { it.from.username })
        assertEquals(listOf("Bob"), requests.outgoing.map { it.to.username })
    }

    @Test
    fun `acceptFriendRequest rejects users that are not the receiver`() {
        val request = service.sendFriendRequest(user("user-a", "Alice"), toUserId = "user-b")

        assertThrows<UnauthorizedException> {
            service.acceptFriendRequest(user("user-c", "Cara"), request.requestId)
        }
    }

    @Test
    fun `declineFriendRequest marks request declined and rejects a second decline`() {
        val request = service.sendFriendRequest(user("user-a", "Alice"), toUserId = "user-b")

        val declined = service.declineFriendRequest(user("user-b", "Bob"), request.requestId)

        assertEquals(FriendRequestStatus.DECLINED, declined.status)
        val error = assertThrows<IllegalStateException> {
            service.declineFriendRequest(user("user-b", "Bob"), request.requestId)
        }
        assertTrue(error.message.orEmpty().contains("not pending"))
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
