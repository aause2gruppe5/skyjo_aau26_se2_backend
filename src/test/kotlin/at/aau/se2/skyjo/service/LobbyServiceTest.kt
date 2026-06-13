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
    fun `first player to join becomes host`() {
        val state = service.join("s1", "Alice")

        assertTrue(state.players[0].isHost)
        assertEquals("Alice", state.players[0].nickname)
    }

    @Test
    fun `second player is not host`() {
        service.join("s1", "Alice")
        val state = service.join("s2", "Bob")

        assertFalse(state.players[1].isHost)
    }

    @Test
    fun `joining same session twice is idempotent`() {
        service.join("s1", "Alice")
        val state = service.join("s1", "Alice")

        assertEquals(1, state.players.size)
    }

    @Test
    fun `joining full lobby throws error`() {
        for (i in 1..6) service.join("s$i", "Player$i")

        val ex = assertThrows<IllegalStateException> { service.join("s7", "Player7") }
        assertTrue(ex.message!!.contains("full"))
    }

    @Test
    fun `joining while game is in progress throws error`() {
        service.join("s1", "Alice")
        service.join("s2", "Bob")
        service.startGame("s1")

        val ex = assertThrows<IllegalStateException> { service.join("s3", "Charlie") }
        assertTrue(ex.message!!.contains("in progress"))
    }

    @Test
    fun `leave removes player and broadcasts updated state`() {
        service.join("s1", "Alice")
        val state = service.leave("s1")

        assertEquals(0, state.players.size)
    }

    @Test
    fun `leave on unknown session does nothing`() {
        service.join("s1", "Alice")
        val state = service.leave("unknown")

        assertEquals(1, state.players.size)
    }

    @Test
    fun `host leaving reassigns host to next player`() {
        service.join("s1", "Alice")
        service.join("s2", "Bob")
        val state = service.leave("s1")

        assertEquals(1, state.players.size)
        assertTrue(state.players[0].isHost)
        assertEquals("Bob", state.players[0].nickname)
    }

    @Test
    fun `non-host cannot start game`() {
        service.join("s1", "Alice")
        service.join("s2", "Bob")

        val ex = assertThrows<IllegalStateException> { service.startGame("s2") }
        assertTrue(ex.message!!.contains("host"))
    }

    @Test
    fun `starting game with less than 2 players throws error`() {
        service.join("s1", "Alice")

        val ex = assertThrows<IllegalStateException> { service.startGame("s1") }
        assertTrue(ex.message!!.contains("2 players"))
    }

    @Test
    fun `starting game sets status to IN_GAME`() {
        service.join("s1", "Alice")
        service.join("s2", "Bob")
        val state = service.startGame("s1")

        assertEquals(LobbyStatus.IN_GAME, state.status)
    }

    @Test
    fun `starting legacy lobby twice throws error`() {
        service.join("s1", "Alice")
        service.join("s2", "Bob")
        service.startGame("s1")

        val ex = assertThrows<IllegalStateException> { service.startGame("s1") }

        assertTrue(ex.message!!.contains("not waiting"))
    }

    @Test
    fun `non-lobby player cannot start game`() {
        service.join("s1", "Alice")
        service.join("s2", "Bob")

        val ex = assertThrows<IllegalStateException> { service.startGame("unknown") }
        assertTrue(ex.message!!.contains("not in lobby"))
    }

    @Test
    fun `reset clears lobby and sets status to WAITING`() {
        service.join("s1", "Alice")
        service.join("s2", "Bob")
        service.startGame("s1")

        val state = service.reset()

        assertEquals(0, state.players.size)
        assertEquals(LobbyStatus.WAITING, state.status)
    }

    @Test
    fun `isPlayerInLobby returns true for joined player`() {
        service.join("s1", "Alice")
        assertTrue(service.isPlayerInLobby("s1"))
    }

    @Test
    fun `isPlayerInLobby returns false for unknown player`() {
        assertFalse(service.isPlayerInLobby("unknown"))
    }

    @Test
    fun `all players leaving during game resets status to WAITING`() {
        service.join("s1", "Alice")
        service.join("s2", "Bob")
        service.startGame("s1")

        service.leave("s1")
        val state = service.leave("s2")

        assertEquals(LobbyStatus.WAITING, state.status)
        assertEquals(0, state.players.size)
    }

    @Test
    fun `new players can join after all players leave during game`() {
        service.join("s1", "Alice")
        service.join("s2", "Bob")
        service.startGame("s1")
        service.leave("s1")
        service.leave("s2")

        val state = service.join("s3", "Charlie")

        assertEquals(1, state.players.size)
        assertTrue(state.players[0].isHost)
        assertEquals("Charlie", state.players[0].nickname)
    }

    @Test
    fun `one player leaving during game keeps IN_GAME status`() {
        service.join("s1", "Alice")
        service.join("s2", "Bob")
        service.startGame("s1")

        val state = service.leave("s1")

        assertEquals(LobbyStatus.IN_GAME, state.status)
        assertEquals(1, state.players.size)
    }

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
