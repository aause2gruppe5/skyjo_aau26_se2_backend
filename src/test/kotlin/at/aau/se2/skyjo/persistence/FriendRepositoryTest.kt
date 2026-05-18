package at.aau.se2.skyjo.persistence

import at.aau.se2.skyjo.model.social.FriendRequestStatus
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.datasource.SingleConnectionDataSource

class FriendRepositoryTest {

    private lateinit var authRepository: AuthRepository
    private lateinit var repository: FriendRepository

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
    }

    @Test
    fun `searchUsers excludes current user and matches username case-insensitively`() {
        val results = repository.searchUsers(query = "bo", currentUserId = "user-a", limit = 10)

        assertEquals(listOf("Bob"), results.map { it.username })
    }

    @Test
    fun `friend requests can be listed incoming and outgoing`() {
        repository.createFriendRequest("request-1", fromUserId = "user-a", toUserId = "user-b", now = 1_000L)

        val incoming = repository.listIncomingRequests("user-b")
        val outgoing = repository.listOutgoingRequests("user-a")

        assertEquals("request-1", incoming.single().requestId)
        assertEquals(FriendRequestStatus.PENDING, incoming.single().status)
        assertEquals("request-1", outgoing.single().requestId)
    }

    @Test
    fun `listFriends includes username presence and current lobby`() {
        repository.createFriendship("user-a", "user-b", now = 1_000L)
        repository.createFriendship("user-b", "user-a", now = 1_000L)
        authRepository.setPresence("user-b", connected = true, currentLobbyId = "lobby-1", now = 2_000L)

        val friends = repository.listFriends("user-a")

        assertEquals("Bob", friends.single().username)
        assertTrue(friends.single().online)
        assertEquals("lobby-1", friends.single().currentLobbyId)
    }

    private fun createUser(userId: String, username: String) {
        authRepository.createUser(
            userId = userId,
            username = username,
            passwordHash = "hash-$userId",
            now = 1L,
        )
    }
}
