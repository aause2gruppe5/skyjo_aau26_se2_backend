package at.aau.se2.skyjo.model.lobby

data class LobbyState(
    val lobbyId: String? = null,
    val joinCode: String? = null,
    val players: List<LobbyPlayer> = emptyList(),
    val status: LobbyStatus = LobbyStatus.WAITING,
    val maxPlayers: Int = 6,
)
