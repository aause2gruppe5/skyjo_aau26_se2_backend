package at.aau.se2.skyjo.model.social

enum class FriendRequestStatus {
    PENDING,
    ACCEPTED,
    DECLINED,
}

enum class RelationshipStatus {
    NONE,
    FRIENDS,
    INCOMING_REQUEST,
    OUTGOING_REQUEST,
}

data class SocialUserDto(
    val userId: String,
    val username: String,
    val relationshipStatus: RelationshipStatus = RelationshipStatus.NONE,
)

data class FriendDto(
    val userId: String,
    val username: String,
    val online: Boolean,
    val currentLobbyId: String?,
)

data class FriendRequestDto(
    val requestId: String,
    val from: SocialUserDto,
    val to: SocialUserDto,
    val status: FriendRequestStatus,
    val createdAt: Long,
    val respondedAt: Long?,
)

data class FriendRequestsResponse(
    val incoming: List<FriendRequestDto>,
    val outgoing: List<FriendRequestDto>,
)

data class SendFriendRequestRequest(
    val toUserId: String,
)
