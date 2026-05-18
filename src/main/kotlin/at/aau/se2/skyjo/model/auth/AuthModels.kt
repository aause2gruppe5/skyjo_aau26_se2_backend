package at.aau.se2.skyjo.model.auth

data class RegisterRequest(
    val username: String,
    val password: String,
)

data class LoginRequest(
    val username: String,
    val password: String,
)

data class AuthResponse(
    val token: String,
    val user: AuthUserDto,
)

data class AuthUserDto(
    val userId: String,
    val username: String,
)

data class WsTicketResponse(
    val ticket: String,
    val expiresAt: Long,
)

data class ErrorResponse(
    val message: String,
)
