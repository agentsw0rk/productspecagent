package com.agentwork.productspecagent.api

import com.agentwork.productspecagent.auth.AuthCookieService
import com.agentwork.productspecagent.auth.JwtService
import com.agentwork.productspecagent.config.AuthProperties
import com.agentwork.productspecagent.domain.AuthCredentials
import com.agentwork.productspecagent.domain.AuthMeResponse
import com.agentwork.productspecagent.service.UserService
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/api/v1/auth")
class AuthController(
    private val users: UserService,
    private val jwt: JwtService,
    private val cookies: AuthCookieService,
    private val authProperties: AuthProperties,
) {

    @PostMapping("/register")
    fun register(@RequestBody credentials: AuthCredentials, response: HttpServletResponse): ResponseEntity<AuthMeResponse> {
        val user = users.register(credentials.email, credentials.password)
        cookies.setSessionCookie(response, jwt.sign(user.id, user.email))
        return ResponseEntity.status(HttpStatus.CREATED).body(AuthMeResponse(user.id, user.email, isAdmin(user.email)))
    }

    @PostMapping("/login")
    fun login(@RequestBody credentials: AuthCredentials, response: HttpServletResponse): AuthMeResponse {
        val user = users.authenticate(credentials.email, credentials.password)
        cookies.setSessionCookie(response, jwt.sign(user.id, user.email))
        return AuthMeResponse(user.id, user.email, isAdmin(user.email))
    }

    @PostMapping("/logout")
    fun logout(response: HttpServletResponse): ResponseEntity<Void> {
        cookies.clearSessionCookie(response)
        return ResponseEntity.noContent().build()
    }

    @GetMapping("/me")
    fun me(request: HttpServletRequest): AuthMeResponse {
        val userId = SecurityContextHolder.getContext().authentication?.name
            ?: error("authentication missing — should have been blocked by SecurityFilterChain")
        val email = request.getAttribute("authEmail") as? String ?: ""
        return AuthMeResponse(userId, email, isAdmin(email))
    }

    private fun isAdmin(email: String): Boolean {
        val normalizedEmail = email.trim().lowercase()
        return authProperties.adminEmails.any { configured ->
            val normalizedConfigured = configured.trim().lowercase()
            normalizedConfigured == "*" || normalizedConfigured == normalizedEmail
        }
    }
}
