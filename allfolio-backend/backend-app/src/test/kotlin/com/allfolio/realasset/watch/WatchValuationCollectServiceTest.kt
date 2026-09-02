package com.allfolio.realasset.watch

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.mockito.Mockito.mock
import org.mockito.Mockito.never
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when`
import java.time.LocalDate
import java.time.LocalDateTime

class WatchValuationCollectServiceTest {

    private val client = mock(WatchValuationClient::class.java)
    private val store = mock(WatchValuationCollectService.Store::class.java)
    private val service = WatchValuationCollectService(client, store)

    private val now = LocalDateTime.of(2026, 9, 2, 10, 20)

    private fun response(refKey: String) = WatchValuationResponse(
        ref = refKey,
        refKey = refKey,
        asOf = LocalDate.of(2026, 9, 2),
        windowDays = 30,
        sampleSize = 71,
        median = 16_678_002,
        priceBasis = "ASK",
        confidence = "MEDIUM",
    )

    @Test
    fun `등록된 시계가 없으면 외부를 부르지 않는다`() {
        // 🔴 **0건이 정상이다.** W6(등록 UI) 전까지는 시계를 넣을 경로 자체가 없다.
        // 이걸 실패로 다루면 배포 첫날부터 매일 빨간 잡이 뜨고, 그러면 아무도 안 본다.
        `when`(store.heldRefKeys()).thenReturn(emptyList())

        val summary = service.collect(now)

        assertThat(summary.requested).isZero()
        assertThat(summary.stored).isZero()
        verify(client, never()).valuate(org.mockito.ArgumentMatchers.anyString())
    }

    @Test
    fun `보유한 ref만 부른다`() {
        `when`(store.heldRefKeys()).thenReturn(listOf("126300", "16233"))
        `when`(client.valuate("126300")).thenReturn(response("126300"))
        `when`(client.valuate("16233")).thenReturn(response("16233"))

        val summary = service.collect(now)

        assertThat(summary.requested).isEqualTo(2)
        assertThat(summary.stored).isEqualTo(2)
        verify(store).upsert(response("126300"), now)
        verify(store).upsert(response("16233"), now)
    }

    @Test
    fun `값이 없는 ref는 건너뛰고 나머지는 저장한다`() {
        // 표본 3건 미만이면 서버가 값을 안 준다(W4). 흔한 경로이고 실패가 아니다 —
        // 폴백이 직전 값을 쓴다.
        `when`(store.heldRefKeys()).thenReturn(listOf("126300", "표본없는ref"))
        `when`(client.valuate("126300")).thenReturn(response("126300"))
        `when`(client.valuate("표본없는ref")).thenReturn(null)

        val summary = service.collect(now)

        assertThat(summary.requested).isEqualTo(2)
        assertThat(summary.stored).isEqualTo(1)
        assertThat(summary.skipped).containsExactly("표본없는ref")
    }

    @Test
    fun `ref 하나가 실패해도 나머지는 저장된다`() {
        // 🔴 클라이언트가 예외 대신 null을 주는 계약에 기대는 테스트다. 그 계약이 깨져
        // 예외가 새어 나오면 배치 전체가 죽는다 — 여기서 그걸 문다.
        `when`(store.heldRefKeys()).thenReturn(listOf("죽는ref", "126300", "16233"))
        `when`(client.valuate("죽는ref")).thenReturn(null)
        `when`(client.valuate("126300")).thenReturn(response("126300"))
        `when`(client.valuate("16233")).thenReturn(response("16233"))

        val summary = service.collect(now)

        assertThat(summary.stored).isEqualTo(2)
        assertThat(summary.skipped).containsExactly("죽는ref")
    }

    @Test
    fun `저장 시각은 호출자가 준 값을 그대로 쓴다`() {
        // 배치가 한 번 정한 시각을 전 행에 같은 값으로 넣는다. 저장 시점마다 now()를
        // 부르면 같은 회차의 행들이 몇 초씩 갈려 "이 회차가 언제 돌았나"가 흐려진다.
        `when`(store.heldRefKeys()).thenReturn(listOf("126300"))
        `when`(client.valuate("126300")).thenReturn(response("126300"))

        service.collect(now)

        verify(store).upsert(response("126300"), now)
    }
}
