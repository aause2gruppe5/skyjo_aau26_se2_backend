package at.aau.se2.skyjo.controller

import at.aau.se2.skyjo.model.auth.ErrorResponse
import at.aau.se2.skyjo.model.auth.LoginRequest
import at.aau.se2.skyjo.model.auth.RegisterRequest
import at.aau.se2.skyjo.service.AuthService
import at.aau.se2.skyjo.service.DuplicateUsernameException
import at.aau.se2.skyjo.service.InvalidAuthInputException
import at.aau.se2.skyjo.service.InvalidCredentialsException
import at.aau.se2.skyjo.service.UnauthorizedException
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/api/auth")
class AuthController(
    private val authService: AuthService,
    private val authSupport: AuthSupport,
) {

    @PostMapping("/register")
    fun register(@RequestBody request: RegisterRequest): ResponseEntity<Any> =
        try {
            ResponseEntity.status(HttpStatus.CREATED).body(authService.register(request.username, request.password))
        } catch (e: DuplicateUsernameException) {
            ResponseEntity.status(HttpStatus.CONFLICT).body(ErrorResponse(e.message ?: "Username is already taken"))
        } catch (e: InvalidAuthInputException) {
            ResponseEntity.badRequest().body(ErrorResponse(e.message ?: "Invalid registration data"))
        }

    @PostMapping("/login")
    fun login(@RequestBody request: LoginRequest): ResponseEntity<Any> =
        try {
            ResponseEntity.ok(authService.login(request.username, request.password))
        } catch (e: InvalidCredentialsException) {
            ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(ErrorResponse(e.message ?: "Invalid username or password"))
        }

    @PostMapping("/logout")
    fun logout(@RequestHeader("Authorization") authorizationHeader: String?): ResponseEntity<Void> {
        val token = authSupport.extractBearerToken(authorizationHeader)
            ?: return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build()
        authService.logout(token)
        return ResponseEntity.noContent().build()
    }

    @GetMapping("/me")
    fun me(@RequestHeader("Authorization") authorizationHeader: String?): ResponseEntity<Any> =
        try {
            ResponseEntity.ok(authSupport.requireUser(authorizationHeader))
        } catch (e: UnauthorizedException) {
            ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(ErrorResponse(e.message ?: "Authentication required"))
        }

    @PostMapping("/ws-ticket")
    fun createWebSocketTicket(@RequestHeader("Authorization") authorizationHeader: String?): ResponseEntity<Any> =
        try {
            val token = authSupport.extractBearerToken(authorizationHeader) ?: throw UnauthorizedException()
            ResponseEntity.ok(authService.createWebSocketTicket(token))
        } catch (e: UnauthorizedException) {
            ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(ErrorResponse(e.message ?: "Authentication required"))
        }
}
