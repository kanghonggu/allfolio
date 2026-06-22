package com.allfolio.auth

import io.jsonwebtoken.JwtException
import io.jsonwebtoken.Jwts
import io.jsonwebtoken.security.Keys
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Service
import java.time.Instant
import java.util.Date
import java.util.UUID
import javax.crypto.SecretKey

@Service
class JwtTokenService(
    @Value("\${allfolio.auth.jwt-secret}") secret: String,
    @Value("\${allfolio.auth.access-token-minutes}") private val accessTokenMinutes: Long,
) {
    private val key: SecretKey = Keys.hmacShaKeyFor(secret.toByteArray().copyOf(32))

    fun issue(user: UserEntity): Pair<String, Long> {
        val now = Instant.now()
        val expiresAt = now.plusSeconds(accessTokenMinutes * 60)
        val token = Jwts.builder()
            .subject(user.id.toString())
            .claim("email", user.email)
            .claim("name", user.displayName ?: user.email)
            .issuedAt(Date.from(now))
            .expiration(Date.from(expiresAt))
            .signWith(key)
            .compact()
        return token to accessTokenMinutes * 60
    }

    fun parseUserId(token: String): UUID {
        try {
            val subject = Jwts.parser()
                .verifyWith(key)
                .build()
                .parseSignedClaims(token)
                .payload
                .subject
            return UUID.fromString(subject)
        } catch (e: IllegalArgumentException) {
            throw JwtException("Invalid subject", e)
        }
    }
}
