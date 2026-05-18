package at.aau.se2.skyjo.controller

import at.aau.se2.skyjo.model.auth.AuthUserDto
import at.aau.se2.skyjo.model.social.LobbyInviteDto
import at.aau.se2.skyjo.model.social.LobbyInviteRequest
import at.aau.se2.skyjo.model.social.LobbyInviteStatus
import at.aau.se2.skyjo.model.social.SocialUserDto
import at.aau.se2.skyjo.service.AuthService
import at.aau.se2.skyjo.service.LobbyInviteService
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.mockito.kotlin.mock
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
    fun `acceptInvite returns accepted invite`() {
        whenever(authService.requireUser("token")).thenReturn(user("user-b", "Bob"))
        whenever(inviteService.acceptInvite(user("user-b", "Bob"), "invite-1")).thenReturn(
            invite(status = LobbyInviteStatus.ACCEPTED),
        )

        val result = controller.acceptInvite("invite-1", "Bearer token")

        assertEquals(HttpStatus.OK, result.statusCode)
        assertEquals(LobbyInviteStatus.ACCEPTED, (result.body as LobbyInviteDto).status)
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
        respondedAt = null,
    )
}
