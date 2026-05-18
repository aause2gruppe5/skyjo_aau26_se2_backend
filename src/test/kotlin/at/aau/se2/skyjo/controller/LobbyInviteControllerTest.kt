package at.aau.se2.skyjo.controller

import at.aau.se2.skyjo.model.auth.AuthUserDto
import at.aau.se2.skyjo.model.auth.ErrorResponse
import at.aau.se2.skyjo.model.social.LobbyInviteDto
import at.aau.se2.skyjo.model.social.LobbyInviteRequest
import at.aau.se2.skyjo.model.social.LobbyInviteStatus
import at.aau.se2.skyjo.model.social.SocialUserDto
import at.aau.se2.skyjo.service.AuthService
import at.aau.se2.skyjo.service.LobbyInviteService
import at.aau.se2.skyjo.service.UnauthorizedException
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import org.springframework.http.HttpStatus
import org.springframework.messaging.simp.SimpMessageSendingOperations

class LobbyInviteControllerTest {

    private val authService: AuthService = mock()
    private val authSupport = AuthSupport(authService)
    private val inviteService: LobbyInviteService = mock()
    private val messagingTemplate: SimpMessageSendingOperations = mock()
    private val controller = LobbyInviteController(inviteService, authSupport, messagingTemplate)

    @Test
    fun `createInvite sends invite to user queue`() {
        whenever(authService.requireUser("token")).thenReturn(user())
        whenever(inviteService.createInvite(user(), "lobby-1", "user-b")).thenReturn(invite())

        val result = controller.createInvite("lobby-1", LobbyInviteRequest("user-b"), "Bearer token")

        assertEquals(HttpStatus.CREATED, result.statusCode)
        verify(messagingTemplate).convertAndSendToUser("user-b", "/queue/invites", invite())
    }

    @Test
    fun `createInvite returns unauthorized without sending websocket message`() {
        whenever(authService.requireUser("bad")).thenThrow(UnauthorizedException())

        val result = controller.createInvite("lobby-1", LobbyInviteRequest("user-b"), "Bearer bad")

        assertEquals(HttpStatus.UNAUTHORIZED, result.statusCode)
        assertEquals("Authentication required", (result.body as ErrorResponse).message)
        verify(messagingTemplate, never()).convertAndSendToUser("user-b", "/queue/invites", invite())
    }

    @Test
    fun `listInvites returns pending invites`() {
        whenever(authService.requireUser("token")).thenReturn(user("user-b", "Bob"))
        whenever(inviteService.listInvites(user("user-b", "Bob"))).thenReturn(listOf(invite()))

        val result = controller.listInvites("Bearer token")

        assertEquals(HttpStatus.OK, result.statusCode)
        assertEquals("invite-1", (result.body as List<*>).filterIsInstance<LobbyInviteDto>().single().inviteId)
    }

    @Test
    fun `acceptInvite returns accepted invite`() {
        whenever(authService.requireUser("token")).thenReturn(user("user-b", "Bob"))
        whenever(inviteService.acceptInvite(user("user-b", "Bob"), "invite-1")).thenReturn(
            invite(status = LobbyInviteStatus.ACCEPTED),
        )

        val result = controller.acceptInvite("invite-1", "Bearer token")

        assertEquals(HttpStatus.OK, result.statusCode)
        assertEquals(LobbyInviteStatus.ACCEPTED, (result.body as LobbyInviteDto).status)
    }

    @Test
    fun `declineInvite returns declined invite`() {
        whenever(authService.requireUser("token")).thenReturn(user("user-b", "Bob"))
        whenever(inviteService.declineInvite(user("user-b", "Bob"), "invite-1")).thenReturn(
            invite(status = LobbyInviteStatus.DECLINED),
        )

        val result = controller.declineInvite("invite-1", "Bearer token")

        assertEquals(HttpStatus.OK, result.statusCode)
        assertEquals(LobbyInviteStatus.DECLINED, (result.body as LobbyInviteDto).status)
    }

    @Test
    fun `invite endpoints map not found errors to not found`() {
        whenever(authService.requireUser("token")).thenReturn(user("user-b", "Bob"))
        whenever(inviteService.acceptInvite(user("user-b", "Bob"), "missing")).thenThrow(
            IllegalStateException("lobby invite not found"),
        )

        val result = controller.acceptInvite("missing", "Bearer token")

        assertEquals(HttpStatus.NOT_FOUND, result.statusCode)
        assertEquals("lobby invite not found", (result.body as ErrorResponse).message)
    }

    @Test
    fun `invite endpoints map invalid operations to bad request`() {
        whenever(authService.requireUser("token")).thenReturn(user())
        whenever(inviteService.createInvite(user(), "lobby-1", "user-b")).thenThrow(
            IllegalStateException("lobby invite already exists"),
        )

        val result = controller.createInvite("lobby-1", LobbyInviteRequest("user-b"), "Bearer token")

        assertEquals(HttpStatus.BAD_REQUEST, result.statusCode)
        assertEquals("lobby invite already exists", (result.body as ErrorResponse).message)
    }

    private fun user(userId: String = "user-a", username: String = "Alice") =
        AuthUserDto(userId = userId, username = username)

    private fun invite(status: LobbyInviteStatus = LobbyInviteStatus.PENDING) = LobbyInviteDto(
        inviteId = "invite-1",
        lobbyId = "lobby-1",
        joinCode = "ABC123",
        from = SocialUserDto("user-a", "Alice"),
        to = SocialUserDto("user-b", "Bob"),
        status = status,
        createdAt = 1_000L,
        respondedAt = if (status == LobbyInviteStatus.PENDING) null else 2_000L,
    )
}
