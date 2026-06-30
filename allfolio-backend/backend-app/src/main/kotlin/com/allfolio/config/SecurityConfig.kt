package com.allfolio.config

import org.springframework.boot.web.servlet.FilterRegistrationBean
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.beans.factory.annotation.Value
import org.springframework.http.HttpMethod
import org.springframework.http.HttpStatus
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer
import org.springframework.security.config.annotation.web.builders.HttpSecurity
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity
import org.springframework.security.config.http.SessionCreationPolicy
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder
import org.springframework.security.crypto.password.PasswordEncoder
import org.springframework.security.web.SecurityFilterChain
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter
import org.springframework.web.cors.CorsConfiguration
import org.springframework.web.cors.CorsConfigurationSource
import org.springframework.web.cors.UrlBasedCorsConfigurationSource

@Configuration
@EnableWebSecurity
class SecurityConfig(
    private val jwtUserIdFilter: JwtUserIdFilter,
    private val sseTokenFilter: SseTokenFilter,
    @Value("\${allfolio.cors.allowed-origins}") private val allowedOrigins: String,
) {

    @Bean
    fun securityFilterChain(http: HttpSecurity): SecurityFilterChain {
        http
            .csrf(AbstractHttpConfigurer<*, *>::disable)
            .cors { it.configurationSource(corsConfigurationSource()) }
            .sessionManagement { it.sessionCreationPolicy(SessionCreationPolicy.STATELESS) }
            .exceptionHandling {
                it.authenticationEntryPoint { request, response, _ ->
                    val status = if (request.requestURI.startsWith("/api/admin/")) {
                        HttpStatus.FORBIDDEN
                    } else {
                        HttpStatus.UNAUTHORIZED
                    }
                    response.sendError(status.value())
                }
            }
            .authorizeHttpRequests { auth ->
                auth
                    .requestMatchers(HttpMethod.OPTIONS, "/**").permitAll()  // CORS preflight
                    .requestMatchers("/actuator/health", "/actuator/health/**").permitAll()
                    .requestMatchers("/actuator/**").authenticated()
                    .requestMatchers("/api/auth/register", "/api/auth/login", "/api/auth/refresh").permitAll()
                    .requestMatchers("/api/broker/*/callback").permitAll()
                    .requestMatchers("/api/sse/prices").permitAll()
                    .requestMatchers("/api/sse/pnl/**").authenticated()
                    .requestMatchers("/api/admin/**").denyAll()
                    .anyRequest().authenticated()
            }
            .addFilterBefore(jwtUserIdFilter, UsernamePasswordAuthenticationFilter::class.java)
            .addFilterBefore(sseTokenFilter, JwtUserIdFilter::class.java)
        return http.build()
    }

    @Bean
    fun sseTokenFilterRegistration(): FilterRegistrationBean<SseTokenFilter> =
        FilterRegistrationBean(sseTokenFilter).apply {
            isEnabled = false
        }

    @Bean
    fun passwordEncoder(): PasswordEncoder = BCryptPasswordEncoder()

    @Bean
    fun corsConfigurationSource(): CorsConfigurationSource {
        val config = CorsConfiguration()
        config.allowedOrigins = allowedOrigins.split(",").map { it.trim() }.filter { it.isNotBlank() }
        config.allowedMethods = listOf("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS")
        config.allowedHeaders = listOf("Authorization", "Content-Type", "X-User-Id")
        config.exposedHeaders = listOf("Content-Type")
        config.allowCredentials = false

        val source = UrlBasedCorsConfigurationSource()
        source.registerCorsConfiguration("/**", config)
        return source
    }
}
