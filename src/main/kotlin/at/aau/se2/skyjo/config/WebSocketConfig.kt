package at.aau.se2.skyjo.config

import at.aau.se2.skyjo.model.auth.AuthPrincipal
import at.aau.se2.skyjo.service.AuthService
import at.aau.se2.skyjo.service.UnauthorizedException
import org.springframework.context.annotation.Configuration
import org.springframework.http.HttpStatus
import org.springframework.http.server.ServerHttpRequest
import org.springframework.http.server.ServerHttpResponse
import org.springframework.messaging.simp.config.MessageBrokerRegistry
import org.springframework.web.socket.WebSocketHandler
import org.springframework.web.socket.config.annotation.EnableWebSocketMessageBroker
import org.springframework.web.socket.config.annotation.StompEndpointRegistry
import org.springframework.web.socket.config.annotation.WebSocketMessageBrokerConfigurer
import org.springframework.web.socket.server.HandshakeInterceptor
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
            .addInterceptors(TicketHandshakeInterceptor(authService))
            .setHandshakeHandler(AuthPrincipalHandshakeHandler())
    }
}

class TicketHandshakeInterceptor(
    private val authService: AuthService,
) : HandshakeInterceptor {

    override fun beforeHandshake(
        request: ServerHttpRequest,
        response: ServerHttpResponse,
        wsHandler: WebSocketHandler,
        attributes: MutableMap<String, Any>,
    ): Boolean {
        val ticket = extractTicket(request)
        val user = if (ticket != null) authService.consumeWebSocketTicket(ticket) else null
        return if (user != null) {
            attributes[ATTR_PRINCIPAL] = AuthPrincipal(userId = user.userId, username = user.username)
            true
        } else {
            response.setStatusCode(HttpStatus.UNAUTHORIZED)
            false
        }
    }

    override fun afterHandshake(
        request: ServerHttpRequest,
        response: ServerHttpResponse,
        wsHandler: WebSocketHandler,
        exception: Exception?,
    ) {}

    internal fun extractTicket(request: ServerHttpRequest): String? {
        val rawQuery = request.uri.rawQuery ?: return null
        return rawQuery
            .split("&")
            .asSequence()
            .mapNotNull { parameter ->
                val separatorIndex = parameter.indexOf("=")
                if (separatorIndex <= 0) null
                else parameter.substring(0, separatorIndex) to parameter.substring(separatorIndex + 1)
            }
            .firstOrNull { (key, _) -> key == "ticket" }
            ?.second
            ?.let { URLDecoder.decode(it, Charsets.UTF_8.name()) }
            ?.ifBlank { null }
    }

    companion object {
        const val ATTR_PRINCIPAL = "ws_principal"
    }
}

class AuthPrincipalHandshakeHandler : DefaultHandshakeHandler() {
    public override fun determineUser(
        request: ServerHttpRequest,
        wsHandler: WebSocketHandler,
        attributes: Map<String, Any>,
    ): Principal? = attributes[TicketHandshakeInterceptor.ATTR_PRINCIPAL] as? Principal
}
