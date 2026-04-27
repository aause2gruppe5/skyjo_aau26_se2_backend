package at.aau.se2.skyjo.controller

import at.aau.se2.skyjo.service.LobbyService
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/admin")
class AdminController(private val lobbyService: LobbyService) {

    @PostMapping("/lobby/reset")
    fun resetLobby(): Map<String, String> {
        lobbyService.reset()
        return mapOf("status" to "lobby reset")
    }
}
