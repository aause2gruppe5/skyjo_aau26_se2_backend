package at.aau.se2.skyjo.service

import at.aau.se2.skyjo.model.auth.AuthUserDto
import at.aau.se2.skyjo.model.lobby.LobbyStatus
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class LobbyServiceTest {

    private lateinit var service: LobbyService

    @BeforeEach
    fun setUp() {
        service = LobbyService()
    }

    private fun authUser(userId: String, username: String) = AuthUserDto(userId = userId, username = username)

    @Test
    fun `createLobby blocks user who already has a WAITING lobby`() {
        service.createLobby(authUser("user-a", "Alice"))

        val ex = assertThrows<IllegalStateException> { service.createLobby(authUser("user-a", "Alice")) }
        assertTrue(ex.message!!.contains("already in a lobby"))
    }

    @Test
    fun `createLobby blocks user who is in an IN_GAME lobby`() {
        val lobby = service.createLobby(authUser("user-a", "Alice"))
        service.joinLobby(authUser("user-b", "Bob"), lobby.joinCode!!)
        service.startGame(userId = "user-a", lobbyId = lobby.lobbyId!!)

        val ex = assertThrows<IllegalStateException> { service.createLobby(authUser("user-a", "Alice")) }
        assertTrue(ex.message!!.contains("already in a lobby"))
    }

    @Test
    fun `createLobby allows user whose previous lobby is CLOSED`() {
        val lobby = service.createLobby(authUser("user-a", "Alice"))
        service.leaveLobby("user-a", lobby.lobbyId!!)

        val newLobby = service.createLobby(authUser("user-a", "Alice"))
        assertNotNull(newLobby.lobbyId)
    }

    @Test
    fun `closeLobby clears in-memory current lobby mappings`() {
        val oldLobby = service.createLobby(authUser("user-a", "Alice"))
        val oldLobbyId = oldLobby.lobbyId!!
        service.joinLobby(authUser("user-b", "Bob"), oldLobby.joinCode!!)
        service.closeLobby(oldLobbyId)
        val newLobby = service.createLobby(authUser("user-c", "Cara"))

        val joined = service.joinLobby(authUser("user-a", "Alice"), newLobby.joinCode!!)

        assertEquals(LobbyStatus.CLOSED, service.getLobbyById(oldLobbyId)?.status)
        assertNull(service.getCurrentLobbyForUser("user-b"))
        assertEquals(newLobby.lobbyId, joined.lobbyId)
    }

    @Test
    fun `closeLobby returns null for unknown lobby`() {
        assertNull(service.closeLobby("missing-lobby"))
    }
}
