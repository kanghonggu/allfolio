package com.allfolio.broker.kis

import com.allfolio.dlq.DlqService
import com.allfolio.metrics.BrokerMetrics
import com.allfolio.trade.infrastructure.outbox.OutboxRepository
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.mockito.ArgumentMatchers
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.TimeZone
import java.util.UUID

/**
 * KIS 체결내역 조회 창(`from`~`to`)은 **한국 거래소 달력의 날짜**여야 한다. `inquire-daily-ccld`에
 * 넘기는 `INQR_STRT_DT`/`INQR_END_DT`를 KIS가 그렇게 해석한다.
 *
 * 여기 있던 `LocalDate.now()`는 **호스트 벽시계 기준 날짜**다. 운영(Render) 컨테이너는 UTC라
 * KST 00:00~09:00 사이에 폴러가 돌면 "어제"를 오늘로 잡아 그날 체결분이 창 밖으로 빠진다.
 * 90일 창이라 실질 영향은 경계 하루지만, 틀린 이유는 [KisTradeMapper]의 `executedAt`과 같다 —
 * **호스트 시계로 한국 거래소 날짜를 셌다.**
 *
 * 값 자체를 `now()`로 단언하면 실행 시각에 따라 경계를 안 넘어 틀린 구현도 통과한다. 그래서
 * **서로 25시간 떨어진 두 호스트 존**에서 같은 창이 나오는지를 본다 — 25시간이면 두 존의 로컬
 * 날짜가 어느 순간에도 같을 수 없으므로, 호스트 시계가 새어 들어오면 반드시 갈린다.
 */
class KisAdapterQueryWindowTest {

    private val DATE_FMT: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyyMMdd")
    private val portfolioId: UUID = UUID.randomUUID()

    @Suppress("UNCHECKED_CAST")
    private fun <T> anyArg(): T = ArgumentMatchers.any<T>() ?: null as T

    /** 조회 창을 잡아내고 빈 성공 응답을 돌려준다. */
    private fun captureWindow(hostZone: String): Pair<String, String> {
        var window: Pair<String, String>? = null
        val client = mock(KisApiClient::class.java)
        `when`(client.resolveAccessToken(portfolioId)).thenReturn("token")
        `when`(client.getDailyOrders(anyArg(), anyArg(), anyArg(), anyArg(), anyArg(), anyArg()))
            .thenAnswer { inv ->
                window = inv.getArgument<String>(3) to inv.getArgument<String>(4)
                KisOrderHistoryResponse(rtCd = "0")
            }

        val adapter = KisAdapter(
            kisApiClient = client,
            kisProperties = mock(KisProperties::class.java),
            dlqService = mock(DlqService::class.java),
            metrics = BrokerMetrics(
                SimpleMeterRegistry(), mock(DlqService::class.java), mock(OutboxRepository::class.java),
            ),
            objectMapper = jacksonObjectMapper(),
        )

        inTimeZone(hostZone) { adapter.fetchTrades(portfolioId, "50123456_01", "") }
        return window!!
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

    @Test
    fun `조회 창은 호스트 타임존에 흔들리지 않는다`() {
        // UTC-11과 UTC+14 — 로컬 시각이 25시간 벌어져 있어 두 존의 날짜는 절대 같을 수 없다.
        val fromMidway     = captureWindow("Pacific/Midway")
        val fromKiritimati = captureWindow("Pacific/Kiritimati")

        assertThat(fromMidway)
            .describedAs("호스트 시계로 한국 거래소 날짜를 세면 서버가 어디서 도느냐에 따라 창이 갈린다")
            .isEqualTo(fromKiritimati)
    }

    @Test
    fun `조회 창의 끝은 한국 달력의 오늘이다`() {
        val (_, toDate) = captureWindow("UTC")

        assertThat(toDate)
            .describedAs("KIS는 한국 거래소 달력으로 해석한다. UTC 호스트에서 새벽에 돌면 어제가 오늘이 된다")
            .isEqualTo(LocalDate.now(ZoneId.of("Asia/Seoul")).format(DATE_FMT))
    }

    @Test
    fun `조회 창은 90일이다`() {
        val (fromDate, toDate) = captureWindow("UTC")

        assertThat(LocalDate.parse(fromDate, DATE_FMT))
            .isEqualTo(LocalDate.parse(toDate, DATE_FMT).minusDays(90))
    }
}
