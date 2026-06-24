package at.aau.se2.skyjo.service

import at.aau.se2.skyjo.model.auth.AuthUserDto
import at.aau.se2.skyjo.model.social.LobbyInviteDto
import at.aau.se2.skyjo.model.social.LobbyInviteStatus
import at.aau.se2.skyjo.model.social.RelationshipStatus
import at.aau.se2.skyjo.model.social.SocialUserDto
import at.aau.se2.skyjo.persistence.AuthRepository
import at.aau.se2.skyjo.persistence.FriendRepository
import at.aau.se2.skyjo.persistence.LobbyInviteRecord
import at.aau.se2.skyjo.persistence.LobbyInviteRepository
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.stereotype.Service
import java.util.UUID

interface LobbyInviteIdGenerator {
    fun generateId(): String
}

class RandomLobbyInviteIdGenerator : LobbyInviteIdGenerator {
    override fun generateId(): String = UUID.randomUUID().toString()
}

@Service
class LobbyInviteService @Autowired constructor(
    private val repository: LobbyInviteRepository,
    private val authRepository: AuthRepository,
    private val friendRepository: FriendRepository,
    private val lobbyService: LobbyService,
) {

    private var inviteIdGenerator: LobbyInviteIdGenerator = RandomLobbyInviteIdGenerator()
    private var nowProvider: () -> Long = { System.currentTimeMillis() }

    internal constructor(
        repository: LobbyInviteRepository,
        authRepository: AuthRepository,
        friendRepository: FriendRepository,
        lobbyService: LobbyService,
        inviteIdGenerator: LobbyInviteIdGenerator,
        nowProvider: () -> Long,
    ) : this(repository, authRepository, friendRepository, lobbyService) {
        this.inviteIdGenerator = inviteIdGenerator
        this.nowProvider = nowProvider
    }

    fun createInvite(from: AuthUserDto, lobbyId: String, toUserId: String): LobbyInviteDto {
        if (from.userId == toUserId) {
            error("cannot invite yourself")
        }
        authRepository.findUserById(toUserId) ?: error("user not found")
        if (!friendRepository.areFriends(from.userId, toUserId)) {
            error("only friends can be invited")
        }
        val lobby = lobbyService.getLobbyById(lobbyId) ?: error("lobby not found")
        lobby.requireOpenForNewPlayers("invite")
        if (lobby.players.none { it.userId == from.userId }) {
            error("only a lobby member can invite")
        }
        if (lobby.players.any { it.userId == toUserId }) {
            error("user is already in the lobby")
        }
        lobby.requireAvailableSlot("invite")
        if (repository.findPendingInvite(lobbyId, toUserId) != null) {
            error("lobby invite already exists")
        }

        repository.createInvite(
            inviteId = inviteIdGenerator.generateId(),
            lobbyId = lobbyId,
            joinCode = requireNotNull(lobby.joinCode) { "join code is missing" },
            fromUserId = from.userId,
            toUserId = toUserId,
            now = nowProvider(),
        )
        return repository.findPendingInvite(lobbyId, toUserId)?.toDto(from.userId) ?: error("lobby invite not found")
    }

    fun listInvites(user: AuthUserDto): List<LobbyInviteDto> =
        repository.listPendingInvitesForUser(user.userId).map { it.toDto(user.userId) }

    fun acceptInvite(user: AuthUserDto, inviteId: String): LobbyInviteDto {
        val invite = requirePendingInviteForUser(user, inviteId)
        val lobby = lobbyService.getLobbyById(invite.lobbyId) ?: error("lobby not found")
        lobby.requireOpenForNewPlayers("accept invite")

        lobbyService.joinLobby(user, invite.joinCode)
        return repository.updateInviteStatus(inviteId, LobbyInviteStatus.ACCEPTED, nowProvider())?.toDto(user.userId)
            ?: error("lobby invite not found")
    }

    fun declineInvite(user: AuthUserDto, inviteId: String): LobbyInviteDto {
        requirePendingInviteForUser(user, inviteId)
        return repository.updateInviteStatus(inviteId, LobbyInviteStatus.DECLINED, nowProvider())?.toDto(user.userId)
            ?: error("lobby invite not found")
    }

    private fun requirePendingInviteForUser(user: AuthUserDto, inviteId: String): LobbyInviteRecord {
        val invite = repository.findInviteById(inviteId) ?: error("lobby invite not found")
        if (invite.toUserId != user.userId) {
            throw UnauthorizedException()
        }
        if (invite.status != LobbyInviteStatus.PENDING) {
            error("lobby invite is not pending")
        }
        return invite
    }

    private fun relationshipStatus(viewerId: String, targetUserId: String): RelationshipStatus =
        when {
            friendRepository.areFriends(viewerId, targetUserId) -> RelationshipStatus.FRIENDS
            friendRepository.findPendingRequest(viewerId, targetUserId) != null -> RelationshipStatus.OUTGOING_REQUEST
            friendRepository.findPendingRequest(targetUserId, viewerId) != null -> RelationshipStatus.INCOMING_REQUEST
            else -> RelationshipStatus.NONE
        }

    private fun LobbyInviteRecord.toDto(viewerId: String) =
        LobbyInviteDto(
            inviteId = inviteId,
            lobbyId = lobbyId,
            joinCode = joinCode,
            from = SocialUserDto(fromUserId, fromUsername, relationshipStatus(viewerId, fromUserId)),
            to = SocialUserDto(toUserId, toUsername, relationshipStatus(viewerId, toUserId)),
            status = status,
            createdAt = createdAt,
            respondedAt = respondedAt,
        )
}
