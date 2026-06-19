package at.aau.se2.skyjo.service

import at.aau.se2.skyjo.model.lobby.LobbyState
import at.aau.se2.skyjo.model.lobby.LobbyStatus

internal fun LobbyState.requireOpenForNewPlayers(operation: String) {
    when (status) {
        LobbyStatus.IN_GAME -> error("cannot $operation: game already in progress")
        LobbyStatus.CLOSED -> error("cannot $operation: lobby is closed")
        else -> Unit
    }
}

internal fun LobbyState.requireAvailableSlot(operation: String) {
    if (players.size >= maxPlayers) {
        error("cannot $operation: lobby is full (max $maxPlayers players)")
    }
}
