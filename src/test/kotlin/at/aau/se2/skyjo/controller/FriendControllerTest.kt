package at.aau.se2.skyjo.controller

import at.aau.se2.skyjo.model.auth.AuthUserDto
import at.aau.se2.skyjo.model.auth.ErrorResponse
import at.aau.se2.skyjo.model.social.FriendDto
import at.aau.se2.skyjo.model.social.FriendRequestDto
import at.aau.se2.skyjo.model.social.FriendRequestStatus
import at.aau.se2.skyjo.model.social.SendFriendRequestRequest
import at.aau.se2.skyjo.model.social.SocialUserDto
import at.aau.se2.skyjo.service.AuthService
import at.aau.se2.skyjo.service.FriendService
import at.aau.se2.skyjo.service.UnauthorizedException
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.mockito.kotlin.mock
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
    fun `friend endpoints return unauthorized for invalid token`() {
        whenever(authService.requireUser("bad")).thenThrow(UnauthorizedException())

        val result = controller.listFriends("Bearer bad")

        assertEquals(HttpStatus.UNAUTHORIZED, result.statusCode)
        assertEquals("Authentication required", (result.body as ErrorResponse).message)
    }

    private fun user() = AuthUserDto(userId = "user-a", username = "Alice")

    private fun request() = FriendRequestDto(
        requestId = "request-1",
        from = SocialUserDto("user-a", "Alice"),
        to = SocialUserDto("user-b", "Bob"),
        status = FriendRequestStatus.PENDING,
        createdAt = 1_000L,
        respondedAt = null,
    )
}
