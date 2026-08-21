package com.allfolio.dlq

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.SerializationFeature
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatCode
import org.junit.jupiter.api.Test
import org.springframework.http.converter.json.Jackson2ObjectMapperBuilder
import java.time.Duration
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneOffset
import java.util.TimeZone
import kotlin.reflect.full.memberProperties

/**
 * [FailedTradeEvent]는 DTO가 아니라 **Redis 저장 포맷**이다 — [DlqService]가 `writeValueAsString`으로
 * 넣고 `readValue`로 읽는다.
 *
 * 그래서 운영 화면의 시각을 고칠 때 이 클래스의 타입은 건드리지 않았다. `OffsetDateTime`으로
 * 바꾸면 **이미 큐에 들어 있는 항목이 역직렬화에 실패한다.** 그리고 그 실패는 조용하다 —
 * `peekDead`가 `runCatching {}.getOrNull()`로 삼켜서 관리자 목록에서 항목이 그냥 사라진다.
 * 전선은 별도 DTO(`FailedDlqEventResponse`)가 책임진다.
 *
 * 대신 **찍는 시계에는 존을 명시한다.** 값의 의미(UTC 벽시계)를 호스트 TZ에 맡기지 않기 위해서다.
 */
class FailedTradeEventClockTest {

    private val mapper: ObjectMapper = Jackson2ObjectMapperBuilder.json()
        .featuresToDisable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
        .build<ObjectMapper>()

    @Test
    fun `저장 포맷의 createdAt은 LocalDateTime으로 남는다`() {
        val declared = FailedTradeEvent::class.memberProperties.first { it.name == "createdAt" }.returnType

        assertThat(declared.classifier)
            .describedAs("타입을 바꾸면 이미 Redis에 있는 항목이 peekDead에서 조용히 사라진다")
            .isEqualTo(LocalDateTime::class)
    }

    @Test
    fun `기존 Redis 항목은 그대로 다시 읽힌다`() {
        // 지금 큐에 앉아 있는 형태 — 오프셋 없는 문자열
        val queuedJson = """
            {"id":"11111111-1111-1111-1111-111111111111","brokerType":"KIS","accountNo":"1234",
             "payloadType":"TRADE_COMMAND","payload":"{}","errorMessage":"boom","retryCount":5,
             "createdAt":"2026-08-20T15:37:00"}
        """.trimIndent()

        assertThatCode { mapper.readValue(queuedJson, FailedTradeEvent::class.java) }
            .describedAs("배포 시점에 큐가 비어 있으리라는 보장이 없다")
            .doesNotThrowAnyException()
    }

    @Test
    fun `찍는 시계는 호스트 타임존에 흔들리지 않는다`() {
        val event = inTimeZone("Asia/Seoul") {
            FailedTradeEvent(
                brokerType = "KIS", accountNo = "1234", payloadType = FailedTradeEvent.TYPE_TRADE_COMMAND,
                payload = "{}", errorMessage = "boom",
            )
        }

        assertThat(Duration.between(event.createdAt.toInstant(ZoneOffset.UTC), Instant.now()).abs())
            .describedAs("호스트 벽시계를 그대로 적으면 KST 호스트에서 9시간 미래가 저장된다")
            .isLessThan(Duration.ofMinutes(1))
    }

    private fun <T> inTimeZone(zone: String, block: () -> T): T {
        val original = TimeZone.getDefault()
        TimeZone.setDefault(TimeZone.getTimeZone(zone))
        try {
            return block()
        } finally {
            TimeZone.setDefault(original)
        }
    }
}
