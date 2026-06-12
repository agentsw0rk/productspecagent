package com.agentwork.productspecagent.config

import com.agentwork.productspecagent.auth.JwtAuthenticationFilter
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.http.HttpStatus
import org.springframework.http.HttpMethod
import org.springframework.security.config.annotation.web.builders.HttpSecurity
import org.springframework.security.config.http.SessionCreationPolicy
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder
import org.springframework.security.crypto.password.PasswordEncoder
import org.springframework.security.web.SecurityFilterChain
import org.springframework.security.web.authentication.HttpStatusEntryPoint
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter

@Configuration
class SecurityConfig(private val jwtAuthenticationFilter: JwtAuthenticationFilter) {

    @Bean
    fun passwordEncoder(): PasswordEncoder = BCryptPasswordEncoder(10)

    @Bean
    fun securityFilterChain(http: HttpSecurity): SecurityFilterChain {
        http
            .cors {}
            .csrf { it.disable() }
            .sessionManagement { it.sessionCreationPolicy(SessionCreationPolicy.STATELESS) }
            .authorizeHttpRequests { auth ->
                auth
                    .requestMatchers("/api/health").permitAll()
                    .requestMatchers("/api/v1/auth/register", "/api/v1/auth/login", "/api/v1/auth/logout").permitAll()
                    .requestMatchers(HttpMethod.GET, "/api/v1/config/features").permitAll()
                    .requestMatchers(HttpMethod.GET, "/api/v1/wizard-options").permitAll()
                    .requestMatchers("/api/v1/admin/wizard-options", "/api/v1/admin/wizard-options/**").hasRole("ADMIN")
                    .requestMatchers("/api/v1/prompts", "/api/v1/prompts/**").hasRole("ADMIN")
                    .requestMatchers("/api/v1/agent-models", "/api/v1/agent-models/**").hasRole("ADMIN")
                    .requestMatchers("/api/v1/asset-bundles", "/api/v1/asset-bundles/**").hasRole("ADMIN")
                    .requestMatchers(HttpMethod.GET, "/api/v1/projects/*/handoff/handoff.zip").permitAll()
                    .requestMatchers(HttpMethod.POST, "/api/v1/projects/*/living-sync/**").permitAll()
                    .requestMatchers(HttpMethod.POST, "/mcp").permitAll()
                    .anyRequest().authenticated()
            }
            .addFilterBefore(jwtAuthenticationFilter, UsernamePasswordAuthenticationFilter::class.java)
            .exceptionHandling { it.authenticationEntryPoint(HttpStatusEntryPoint(HttpStatus.UNAUTHORIZED)) }
        return http.build()
    }
}
