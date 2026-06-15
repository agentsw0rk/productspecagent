package com.agentwork.productspecagent.domain

data class AuthCredentials(
    val email: String,
    val password: String,
)

data class AuthTokenResponse(
    val token: String,
    val userId: String,
    val email: String,
    val admin: Boolean = false,
)

data class AuthMeResponse(
    val userId: String,
    val email: String,
    val admin: Boolean = false,
)
