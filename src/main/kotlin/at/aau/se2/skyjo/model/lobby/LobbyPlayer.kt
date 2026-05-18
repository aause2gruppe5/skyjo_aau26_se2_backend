package at.aau.se2.skyjo.model.lobby

data class LobbyPlayer(
    val sessionId: String,
    val nickname: String,
    val isHost: Boolean,
    val userId: String = sessionId,
)
