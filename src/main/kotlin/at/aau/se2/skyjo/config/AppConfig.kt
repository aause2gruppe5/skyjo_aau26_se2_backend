package at.aau.se2.skyjo.config

import at.aau.se2.skyjo.game.service.SkyjoEngine
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

@Configuration
class AppConfig {
    @Bean
    fun skyjoEngine(): SkyjoEngine = SkyjoEngine()
}
