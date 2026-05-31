package at.aau.se2.skyjo.model.lobby

import at.aau.se2.skyjo.model.LobbyPlayerInfo

data class LobbySummaryResponse(
    val lobbyId: String,
    val joinCode: String,
    val players: List<LobbyPlayerInfo>,
    val status: LobbyStatus,
    val maxPlayers: Int,
)
