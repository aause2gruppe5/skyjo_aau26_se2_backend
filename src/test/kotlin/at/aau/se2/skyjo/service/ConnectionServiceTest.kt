package at.aau.se2.skyjo.service

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class ConnectionServiceTest {

    private val service = ConnectionService()

    @Test
    fun `registerSession stores player and marks session as connected`() {
        service.registerSession("s1", "Alice")

        assertEquals("Alice", service.getPlayerName("s1"))
        assertTrue(service.isConnected("s1"))
        assertEquals(1, service.getConnectedCount())
    }

    @Test
    fun `removeSession removes and returns player`() {
        service.registerSession("s1", "Alice")

        val removed = service.removeSession("s1")

        assertEquals("Alice", removed)
        assertNull(service.getPlayerName("s1"))
        assertFalse(service.isConnected("s1"))
        assertEquals(0, service.getConnectedCount())
    }

    @Test
    fun `removeSession returns null for unknown session`() {
        assertNull(service.removeSession("unknown"))
    }
}