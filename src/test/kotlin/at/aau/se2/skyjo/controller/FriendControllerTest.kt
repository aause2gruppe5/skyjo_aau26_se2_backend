package at.aau.se2.skyjo.controller

import at.aau.se2.skyjo.model.auth.AuthUserDto
import at.aau.se2.skyjo.model.auth.ErrorResponse
import at.aau.se2.skyjo.model.social.FriendDto
import at.aau.se2.skyjo.model.social.FriendRequestDto
import at.aau.se2.skyjo.model.social.FriendRequestsResponse
import at.aau.se2.skyjo.model.social.FriendRequestStatus
import at.aau.se2.skyjo.model.social.SendFriendRequestRequest
import at.aau.se2.skyjo.model.social.SocialUserDto
import at.aau.se2.skyjo.service.AuthService
import at.aau.se2.skyjo.service.FriendService
import at.aau.se2.skyjo.service.UnauthorizedException
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.mockito.kotlin.mock
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import org.springframework.http.HttpStatus

class FriendControllerTest {

    private val authService: AuthService = mock()
    private val friendService: FriendService = mock()
    private val authSupport = AuthSupport(authService)
    private val controller = FriendController(friendService, authSupport)

    @Test
    fun `searchUsers requires auth and returns users`() {
        whenever(authService.requireUser("token")).thenReturn(user())
        whenever(friendService.searchUsers(user(), "bo")).thenReturn(
            listOf(SocialUserDto("user-b", "Bob")),
        )

        val result = controller.searchUsers("bo", "Bearer token")

        assertEquals(HttpStatus.OK, result.statusCode)
        assertEquals("Bob", (result.body as List<*>).filterIsInstance<SocialUserDto>().single().username)
    }

    @Test
    fun `sendFriendRequest creates request`() {
        whenever(authService.requireUser("token")).thenReturn(user())
        whenever(friendService.sendFriendRequest(user(), "user-b")).thenReturn(request())

        val result = controller.sendFriendRequest(SendFriendRequestRequest("user-b"), "Bearer token")

        assertEquals(HttpStatus.CREATED, result.statusCode)
        assertEquals("request-1", (result.body as FriendRequestDto).requestId)
    }

    @Test
    fun `listFriends returns authenticated friends`() {
        whenever(authService.requireUser("token")).thenReturn(user())
        whenever(friendService.listFriends(user())).thenReturn(
            listOf(FriendDto("user-b", "Bob", online = true, currentLobbyId = "lobby-1")),
        )

        val result = controller.listFriends("Bearer token")

        assertEquals(HttpStatus.OK, result.statusCode)
        assertEquals("Bob", (result.body as List<*>).filterIsInstance<FriendDto>().single().username)
    }

    @Test
    fun `heartbeat records presence and returns no content`() {
        whenever(authService.requireUser("token")).thenReturn(user())

        val result = controller.heartbeat("Bearer token")

        assertEquals(HttpStatus.NO_CONTENT, result.statusCode)
        verify(friendService).recordHeartbeat(user())
    }

    @Test
    fun `heartbeat returns unauthorized for invalid token`() {
        whenever(authService.requireUser("bad")).thenThrow(UnauthorizedException())

        val result = controller.heartbeat("Bearer bad")

        assertEquals(HttpStatus.UNAUTHORIZED, result.statusCode)
    }

    @Test
    fun `listFriendRequests returns incoming and outgoing requests`() {
        whenever(authService.requireUser("token")).thenReturn(user())
        whenever(friendService.listFriendRequests(user())).thenReturn(
            FriendRequestsResponse(
                incoming = listOf(request(requestId = "incoming-1")),
                outgoing = listOf(request(requestId = "outgoing-1")),
            ),
        )

        val result = controller.listFriendRequests("Bearer token")

        assertEquals(HttpStatus.OK, result.statusCode)
        assertEquals("incoming-1", (result.body as FriendRequestsResponse).incoming.single().requestId)
        assertEquals("outgoing-1", (result.body as FriendRequestsResponse).outgoing.single().requestId)
    }

    @Test
    fun `acceptFriendRequest returns accepted request`() {
        whenever(authService.requireUser("token")).thenReturn(user("user-b", "Bob"))
        whenever(friendService.acceptFriendRequest(user("user-b", "Bob"), "request-1")).thenReturn(
            request(status = FriendRequestStatus.ACCEPTED),
        )

        val result = controller.acceptFriendRequest("request-1", "Bearer token")

        assertEquals(HttpStatus.OK, result.statusCode)
        assertEquals(FriendRequestStatus.ACCEPTED, (result.body as FriendRequestDto).status)
    }

    @Test
    fun `declineFriendRequest returns declined request`() {
        whenever(authService.requireUser("token")).thenReturn(user("user-b", "Bob"))
        whenever(friendService.declineFriendRequest(user("user-b", "Bob"), "request-1")).thenReturn(
            request(status = FriendRequestStatus.DECLINED),
        )

        val result = controller.declineFriendRequest("request-1", "Bearer token")

        assertEquals(HttpStatus.OK, result.statusCode)
        assertEquals(FriendRequestStatus.DECLINED, (result.body as FriendRequestDto).status)
    }

    @Test
    fun `friend endpoints return unauthorized for invalid token`() {
        whenever(authService.requireUser("bad")).thenThrow(UnauthorizedException())

        val result = controller.listFriends("Bearer bad")

        assertEquals(HttpStatus.UNAUTHORIZED, result.statusCode)
        assertEquals("Authentication required", (result.body as ErrorResponse).message)
    }

    @Test
    fun `friend endpoints map not found errors to not found`() {
        whenever(authService.requireUser("token")).thenReturn(user("user-b", "Bob"))
        whenever(friendService.acceptFriendRequest(user("user-b", "Bob"), "missing")).thenThrow(
            IllegalStateException("friend request not found"),
        )

        val result = controller.acceptFriendRequest("missing", "Bearer token")

        assertEquals(HttpStatus.NOT_FOUND, result.statusCode)
        assertEquals("friend request not found", (result.body as ErrorResponse).message)
    }

    @Test
    fun `friend endpoints map invalid operations to bad request`() {
        whenever(authService.requireUser("token")).thenReturn(user())
        whenever(friendService.sendFriendRequest(user(), "user-b")).thenThrow(
            IllegalStateException("users are already friends"),
        )

        val result = controller.sendFriendRequest(SendFriendRequestRequest("user-b"), "Bearer token")

        assertEquals(HttpStatus.BAD_REQUEST, result.statusCode)
        assertEquals("users are already friends", (result.body as ErrorResponse).message)
    }

    private fun user(userId: String = "user-a", username: String = "Alice") =
        AuthUserDto(userId = userId, username = username)

    private fun request(
        requestId: String = "request-1",
        status: FriendRequestStatus = FriendRequestStatus.PENDING,
    ) = FriendRequestDto(
        requestId = requestId,
        from = SocialUserDto("user-a", "Alice"),
        to = SocialUserDto("user-b", "Bob"),
        status = status,
        createdAt = 1_000L,
        respondedAt = if (status == FriendRequestStatus.PENDING) null else 2_000L,
    )
}
