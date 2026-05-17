package at.aau.se2.skyjo.controller

import io.mockk.every
import io.mockk.impl.annotations.InjectMockKs
import io.mockk.impl.annotations.MockK
import io.mockk.junit5.MockKExtension
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import at.aau.se2.skyjo.service.*

@ExtendWith(MockKExtension::class)
class AdminControllerTest {

    @MockK
    lateinit var lobbyService: LobbyService

    @InjectMockKs
    lateinit var controller: AdminController

    @Test
    fun `resetLobby ruft reset im LobbyService auf und gibt Status-Map zurueck`() {
        //Wir mocken den Rückgabewert von reset(), da die echte Methode einen LobbyState zurückgibt
        every { lobbyService.reset() } returns mockk()

        //Ausführung
        val result = controller.resetLobby()

        //Verifizierung: Wurde die Methode im Service exakt einmal aufgerufen?
        verify(exactly = 1) { lobbyService.reset() }

        //Verifizierung: Stimmt die zurückgegebene Map?
        val expectedMap = mapOf("status" to "lobby reset")
        assertEquals(expectedMap, result)
    }
}