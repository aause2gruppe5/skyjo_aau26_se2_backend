package at.aau.se2.skyjo.model

import at.aau.se2.skyjo.model.lobby.LobbyStatus

data class LobbyUpdateMessage(
    val lobbyId: String? = null,
    val joinCode: String? = null,
    val players: List<LobbyPlayerInfo>,
    val status: LobbyStatus,
    val maxPlayers: Int,
)

data class LobbyPlayerInfo(
    val nickname: String,
    val isHost: Boolean,
)
