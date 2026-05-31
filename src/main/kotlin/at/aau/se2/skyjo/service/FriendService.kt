package at.aau.se2.skyjo.service

import at.aau.se2.skyjo.model.auth.AuthUserDto
import at.aau.se2.skyjo.model.social.FriendDto
import at.aau.se2.skyjo.model.social.FriendRequestDto
import at.aau.se2.skyjo.model.social.FriendRequestsResponse
import at.aau.se2.skyjo.model.social.FriendRequestStatus
import at.aau.se2.skyjo.model.social.RelationshipStatus
import at.aau.se2.skyjo.model.social.SocialUserDto
import at.aau.se2.skyjo.persistence.AuthRepository
import at.aau.se2.skyjo.persistence.FriendRepository
import at.aau.se2.skyjo.persistence.FriendRequestRecord
import at.aau.se2.skyjo.persistence.FriendUserRecord
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.dao.DataAccessException
import org.springframework.stereotype.Service
import java.util.UUID

interface FriendRequestIdGenerator {
    fun generateId(): String
}

class RandomFriendRequestIdGenerator : FriendRequestIdGenerator {
    override fun generateId(): String = UUID.randomUUID().toString()
}

@Service
class FriendService @Autowired constructor(
    private val repository: FriendRepository,
    private val authRepository: AuthRepository,
) {

    private var requestIdGenerator: FriendRequestIdGenerator = RandomFriendRequestIdGenerator()
    private var nowProvider: () -> Long = { System.currentTimeMillis() }

    internal constructor(
        repository: FriendRepository,
        authRepository: AuthRepository,
        requestIdGenerator: FriendRequestIdGenerator,
        nowProvider: () -> Long,
    ) : this(repository, authRepository) {
        this.requestIdGenerator = requestIdGenerator
        this.nowProvider = nowProvider
    }

    fun searchUsers(user: AuthUserDto, query: String): List<SocialUserDto> {
        val normalizedQuery = query.trim()
        if (normalizedQuery.isBlank()) {
            return emptyList()
        }

        return repository.searchUsers(normalizedQuery, user.userId, SEARCH_LIMIT).map { record ->
            SocialUserDto(
                userId = record.userId,
                username = record.username,
                relationshipStatus = relationshipStatus(user.userId, record.userId),
            )
        }
    }

    fun listFriends(user: AuthUserDto): List<FriendDto> =
        repository.listFriends(user.userId).map { it.toFriendDto() }

    fun listFriendRequests(user: AuthUserDto): FriendRequestsResponse =
        FriendRequestsResponse(
            incoming = repository.listIncomingRequests(user.userId).map { it.toDto(user.userId) },
            outgoing = repository.listOutgoingRequests(user.userId).map { it.toDto(user.userId) },
        )

    fun sendFriendRequest(from: AuthUserDto, toUserId: String): FriendRequestDto {
        if (from.userId == toUserId) {
            error("cannot send friend request to yourself")
        }
        authRepository.findUserById(toUserId) ?: error("user not found")
        if (repository.areFriends(from.userId, toUserId)) {
            error("users are already friends")
        }
        if (repository.findPendingRequest(from.userId, toUserId) != null ||
            repository.findPendingRequest(toUserId, from.userId) != null
        ) {
            error("friend request already exists")
        }

        val requestId = requestIdGenerator.generateId()
        try {
            repository.createFriendRequest(requestId, from.userId, toUserId, nowProvider())
        } catch (e: DataAccessException) {
            if (repository.findPendingRequest(from.userId, toUserId) != null) {
                error("friend request already exists")
            }
            throw e
        }
        return repository.findRequestById(requestId)?.toDto(from.userId) ?: error("friend request not found")
    }

    fun acceptFriendRequest(user: AuthUserDto, requestId: String): FriendRequestDto {
        val request = repository.findRequestById(requestId) ?: error("friend request not found")
        if (request.toUserId != user.userId) {
            throw UnauthorizedException()
        }
        if (request.status != FriendRequestStatus.PENDING) {
            error("friend request is not pending")
        }

        val now = nowProvider()
        val accepted = repository.updateRequestStatus(requestId, FriendRequestStatus.ACCEPTED, now)
            ?: error("friend request not found")
        repository.createFriendship(accepted.fromUserId, accepted.toUserId, now)
        repository.createFriendship(accepted.toUserId, accepted.fromUserId, now)
        return accepted.toDto(user.userId)
    }

    fun declineFriendRequest(user: AuthUserDto, requestId: String): FriendRequestDto {
        val request = repository.findRequestById(requestId) ?: error("friend request not found")
        if (request.toUserId != user.userId) {
            throw UnauthorizedException()
        }
        if (request.status != FriendRequestStatus.PENDING) {
            error("friend request is not pending")
        }
        return repository.updateRequestStatus(requestId, FriendRequestStatus.DECLINED, nowProvider())?.toDto(user.userId)
            ?: error("friend request not found")
    }

    private fun relationshipStatus(currentUserId: String, targetUserId: String): RelationshipStatus =
        when {
            repository.areFriends(currentUserId, targetUserId) -> RelationshipStatus.FRIENDS
            repository.findPendingRequest(currentUserId, targetUserId) != null -> RelationshipStatus.OUTGOING_REQUEST
            repository.findPendingRequest(targetUserId, currentUserId) != null -> RelationshipStatus.INCOMING_REQUEST
            else -> RelationshipStatus.NONE
        }

    private fun FriendUserRecord.toFriendDto() =
        FriendDto(
            userId = userId,
            username = username,
            online = online,
            currentLobbyId = currentLobbyId,
        )

    private fun FriendRequestRecord.toDto(viewerId: String) =
        FriendRequestDto(
            requestId = requestId,
            from = SocialUserDto(fromUserId, fromUsername, relationshipStatus(viewerId, fromUserId)),
            to = SocialUserDto(toUserId, toUsername, relationshipStatus(viewerId, toUserId)),
            status = status,
            createdAt = createdAt,
            respondedAt = respondedAt,
        )

    private companion object {
        const val SEARCH_LIMIT = 20
    }
}
