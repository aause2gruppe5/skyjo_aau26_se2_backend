package at.aau.se2.skyjo.model.auth

import java.security.Principal

data class AuthPrincipal(
    val userId: String,
    val username: String,
) : Principal {
    override fun getName(): String = userId
}
