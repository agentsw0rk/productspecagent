package com.agentwork.productspecagent.config

import org.springframework.boot.context.properties.ConfigurationProperties

@ConfigurationProperties(prefix = "auth")
data class AuthProperties(
    val jwt: Jwt = Jwt(),
    val adminEmails: List<String> = emptyList(),
) {
    data class Jwt(
        val secret: String = "",
        val expirySeconds: Long = 604800, // 7 days
    )
}
