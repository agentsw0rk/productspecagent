package com.agentwork.productspecagent.auth

import com.agentwork.productspecagent.config.AuthProperties
import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken
import org.springframework.security.core.authority.SimpleGrantedAuthority
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.stereotype.Component
import org.springframework.web.filter.OncePerRequestFilter

@Component
class JwtAuthenticationFilter(
    private val jwtService: JwtService,
    private val props: AuthProperties,
) : OncePerRequestFilter() {

    override fun doFilterInternal(request: HttpServletRequest, response: HttpServletResponse, filterChain: FilterChain) {
        val token = request.getHeader("Authorization")
            ?.takeIf { it.startsWith("Bearer ", ignoreCase = true) }
            ?.substringAfter(" ")
            ?.trim()
            ?.takeIf { it.isNotBlank() }
        if (token != null) {
            val payload = jwtService.parse(token)
            if (payload != null) {
                val authorities = if (isAdminEmail(payload.email)) {
                    listOf(SimpleGrantedAuthority("ROLE_ADMIN"))
                } else {
                    emptyList()
                }
                val auth = UsernamePasswordAuthenticationToken(
                    payload.userId,
                    null,
                    authorities
                )
                SecurityContextHolder.getContext().authentication = auth
                request.setAttribute("authEmail", payload.email)
            }
        }
        filterChain.doFilter(request, response)
    }

    private fun isAdminEmail(email: String): Boolean {
        val normalizedEmail = email.trim().lowercase()
        return props.adminEmails.any { configured ->
            val normalizedConfigured = configured.trim().lowercase()
            normalizedConfigured == "*" || normalizedConfigured == normalizedEmail
        }
    }
}
