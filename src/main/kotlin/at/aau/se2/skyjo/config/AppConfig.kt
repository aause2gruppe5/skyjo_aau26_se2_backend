package at.aau.se2.skyjo.config

import at.aau.se2.skyjo.game.service.SkyjoEngine
import at.aau.se2.skyjo.persistence.LobbyRepository
import at.aau.se2.skyjo.service.AuthService
import at.aau.se2.skyjo.service.LobbyService
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

@Configuration
class AppConfig {
    @Bean
    fun skyjoEngine(): SkyjoEngine = SkyjoEngine()

    @Bean
    fun lobbyService(lobbyRepository: LobbyRepository, authService: AuthService): LobbyService =
        LobbyService(repository = lobbyRepository, authService = authService)
}
