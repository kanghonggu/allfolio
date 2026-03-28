package com.allfolio.broker

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.Id
import jakarta.persistence.Index
import jakarta.persistence.Table
import java.time.LocalDateTime
import java.util.UUID

/**
 * 브로커 OAuth2 / API 인증 정보
 *
 * - Binance: secretKey 등은 @ConfigurationProperties로 관리 (DB 저장 불필요)
 * - Toss: OAuth2 AccessToken/RefreshToken → 이 테이블에 사용자별 저장
 */
@Entity
@Table(
    name = "broker_auth",
    indexes = [Index(name = "idx_broker_auth_user_broker", columnList = "user_id, broker_type", unique = true)],
)
class BrokerAuthEntity(

    @Id
    @Column(columnDefinition = "uuid", nullable = false, updatable = false)
    val id: UUID,

    @Column(name = "user_id", columnDefinition = "uuid", nullable = false, updatable = false)
    val userId: UUID,

    @Enumerated(EnumType.STRING)
    @Column(name = "broker_type", nullable = false, updatable = false, length = 20)
    val brokerType: BrokerType,

    @Column(name = "access_token", nullable = false, columnDefinition = "text")
    var accessToken: String,

    @Column(name = "refresh_token", columnDefinition = "text")
    var refreshToken: String? = null,

    @Column(name = "token_type", length = 20)
    var tokenType: String = "Bearer",

    @Column(name = "access_token_expires_at", nullable = false)
    var accessTokenExpiresAt: LocalDateTime,

    @Column(name = "refresh_token_expires_at")
    var refreshTokenExpiresAt: LocalDateTime? = null,

    @Column(name = "created_at", nullable = false, updatable = false)
    val createdAt: LocalDateTime = LocalDateTime.now(),

    @Column(name = "updated_at", nullable = false)
    var updatedAt: LocalDateTime = LocalDateTime.now(),
) {
    fun isAccessTokenExpired(): Boolean = LocalDateTime.now().isAfter(accessTokenExpiresAt.minusMinutes(5))
    fun isRefreshTokenExpired(): Boolean =
        refreshTokenExpiresAt?.let { LocalDateTime.now().isAfter(it) } ?: false
}
