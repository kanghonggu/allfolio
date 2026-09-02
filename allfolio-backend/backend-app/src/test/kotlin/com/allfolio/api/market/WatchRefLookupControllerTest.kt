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
    fun `상류가 정규화하면 그 값을 따라간다`() {
        // ⚠️ **상류는 오늘 정규화하지 않는다.** 실측(2026-09-02) `/api/valuation`은 입력을
        // 그대로 되울린다 — `116238 CHSJ`를 물으면 refKey도 `116238 CHSJ`로 온다.
        // (아래 `상류가 되울린 값을 그대로 쓴다`가 그 실제 동작을 문다.)
        //
        // 그런데도 이 테스트를 남기는 이유는, 우리가 **응답의 키를 쓰지 사용자가 친
        // 문자열을 쓰지 않는다**는 계약을 고정하기 위해서다. 상류가 나중에 정규화를
        // 하게 되면 이쪽은 고칠 것이 없어야 한다.
        //
        // 🔴 이 테스트가 처음엔 "서버가 정규화한다"는 **틀린 전제**를 목으로 만들어
        // 통과시켰다. 목은 내가 믿는 것을 검사하지 상류가 하는 일을 검사하지 않는다.
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
    fun `상류가 되울린 값을 그대로 쓴다 — 오늘의 실제 동작이다`() {
        // 실측: ref=`116238 CHSJ` → refKey=`116238 CHSJ` · 표본 1건.
        // 같은 시계라도 `116238`로 물으면 0건이라 결과가 달라진다. 화면 안내가
        // "적은 그대로 찾는다"고 말하는 근거다.
        `when`(client.valuate("116238 CHSJ")).thenReturn(
            WatchValuationResponse(
                ref = "116238 CHSJ",
                refKey = "116238 CHSJ",
                asOf = LocalDate.of(2026, 9, 2),
                sampleSize = 5,
                median = 47_200_000,
            ),
        )

        assertThat(controller.lookup("116238 CHSJ").body!!.ref).isEqualTo("116238 CHSJ")
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
