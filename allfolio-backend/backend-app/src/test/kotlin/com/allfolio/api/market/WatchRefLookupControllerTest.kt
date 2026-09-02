package com.allfolio.api.market

import com.allfolio.realasset.watch.WatchValuationClient
import com.allfolio.realasset.watch.WatchValuationResponse
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.mockito.Mockito.mock
import org.mockito.Mockito.never
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when`
import java.time.LocalDate

class WatchRefLookupControllerTest {

    private val client = mock(WatchValuationClient::class.java)
    private val controller = WatchRefLookupController(client)

    @Test
    fun `사용자가 친 값이 아니라 서버가 정규화한 ref를 돌려준다`() {
        // 🔴 이 PR의 핵심이다. `116238 CHSJ`를 쳐도 저장되는 값은 `116238`이어야 한다 —
        // 친 문자열을 그대로 symbol에 넣으면 평가 배치가 그 키로 못 찾는다.
        // R2가 단지일련번호로 막았던 불일치를 여기서는 이 반환값이 막는다.
        `when`(client.valuate("116238 CHSJ")).thenReturn(
            WatchValuationResponse(
                ref = "116238 CHSJ",
                refKey = "116238",
                asOf = LocalDate.of(2026, 9, 2),
                windowDays = 30,
                sampleSize = 12,
                median = 47_200_000,
                priceBasis = "ASK",
                confidence = "MEDIUM",
            ),
        )

        val body = controller.lookup("116238 CHSJ").body!!

        assertThat(body.found).isTrue()
        assertThat(body.ref).isEqualTo("116238")
        assertThat(body.medianKrw).isEqualTo(47_200_000)
        assertThat(body.sampleSize).isEqualTo(12)
    }

    @Test
    fun `표본이 없으면 404가 아니라 found false다`() {
        // 등록 자체는 막지 않는다. 시세를 못 구하는 시계도 자산으로는 존재하고,
        // 화면은 "자동 평가가 안 된다"고만 말한다.
        `when`(client.valuate("없는ref")).thenReturn(null)

        val res = controller.lookup("없는ref")

        assertThat(res.statusCode.value()).isEqualTo(200)
        assertThat(res.body!!.found).isFalse()
        assertThat(res.body!!.ref).isEqualTo("없는ref")
    }

    @Test
    fun `공백만 있으면 상류를 부르지 않는다`() {
        val body = controller.lookup("   ").body!!

        assertThat(body.found).isFalse()
        verify(client, never()).valuate(org.mockito.ArgumentMatchers.anyString())
    }

    @Test
    fun `앞뒤 공백을 떼고 부른다`() {
        // 붙여넣기하면 공백이 딸려 온다. 그대로 넘기면 상류가 못 찾는다.
        `when`(client.valuate("126300")).thenReturn(
            WatchValuationResponse(refKey = "126300", median = 16_678_002, asOf = LocalDate.now()),
        )

        assertThat(controller.lookup("  126300  ").body!!.found).isTrue()
        verify(client).valuate("126300")
    }

    @Test
    fun `refKey가 없으면 ref로 떨어진다`() {
        // 상류가 정규화 값을 안 줄 수도 있다. 그때 null을 저장하면 자산에 매칭 키가
        // 없어지므로, 우리가 물어본 값이라도 채운다.
        `when`(client.valuate("1603")).thenReturn(
            WatchValuationResponse(ref = "1603", refKey = null, median = 4_800_000, asOf = LocalDate.now()),
        )

        assertThat(controller.lookup("1603").body!!.ref).isEqualTo("1603")
    }
}
