package at.aau.se2.skyjo.config

import at.aau.se2.skyjo.model.auth.AuthPrincipal
import at.aau.se2.skyjo.service.AuthService
import at.aau.se2.skyjo.service.UnauthorizedException
import org.springframework.context.annotation.Configuration
import org.springframework.http.server.ServerHttpRequest
import org.springframework.messaging.simp.config.MessageBrokerRegistry
import org.springframework.web.socket.WebSocketHandler
import org.springframework.web.socket.config.annotation.EnableWebSocketMessageBroker
import org.springframework.web.socket.config.annotation.StompEndpointRegistry
import org.springframework.web.socket.config.annotation.WebSocketMessageBrokerConfigurer
import org.springframework.web.socket.server.support.DefaultHandshakeHandler
import java.net.URLDecoder
import java.security.Principal

@Configuration
@EnableWebSocketMessageBroker
class WebSocketConfig(
    private val authService: AuthService,
) : WebSocketMessageBrokerConfigurer {

    override fun configureMessageBroker(config: MessageBrokerRegistry) {
        config.enableSimpleBroker("/topic", "/queue")
        config.setApplicationDestinationPrefixes("/app")
        config.setUserDestinationPrefix("/user")
    }

    override fun registerStompEndpoints(registry: StompEndpointRegistry) {
        registry.addEndpoint("/ws")
            .setAllowedOriginPatterns("*")
            .setHandshakeHandler(AuthPrincipalHandshakeHandler(authService))
    }
}

class AuthPrincipalHandshakeHandler(
    private val authService: AuthService,
) : DefaultHandshakeHandler() {
    override fun determineUser(
        request: ServerHttpRequest,
        wsHandler: WebSocketHandler,
        attributes: Map<String, Any>,
    ): Principal = determinePrincipal(request)

    fun determinePrincipal(request: ServerHttpRequest): Principal {
        val ticket = extractTicket(request) ?: throw UnauthorizedException()
        val user = authService.consumeWebSocketTicket(ticket) ?: throw UnauthorizedException()
        return AuthPrincipal(userId = user.userId, username = user.username)
    }

    private fun extractTicket(request: ServerHttpRequest): String? {
        val rawQuery = request.uri.rawQuery ?: return null
        return rawQuery
            .split("&")
            .asSequence()
            .mapNotNull { parameter ->
                val separatorIndex = parameter.indexOf("=")
                if (separatorIndex <= 0) {
                    null
                } else {
                    parameter.substring(0, separatorIndex) to parameter.substring(separatorIndex + 1)
                }
            }
            .firstOrNull { (key, _) -> key == "ticket" }
            ?.second
            ?.let { URLDecoder.decode(it, Charsets.UTF_8.name()) }
            ?.ifBlank { null }
    }
}
