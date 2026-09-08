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
    fun `상류가 정규화한 키를 따라간다 — 사용자가 친 문자열이 아니다`() {
        // 실측(2026-09-08, api.watchpricedata.com):
        //     `126300ln`          → refKey `126300LN`
        //     `  116238   chsj  ` → refKey `116238 CHSJ` · 표본 1건
        // 대소문자·공백·괄호는 접힌다. 여기서 무는 계약은 **우리가 응답의 키를 쓰지
        // 사용자가 친 문자열을 쓰지 않는다**는 것이다.
        //
        // 🔴 이 테스트는 두 번 틀렸다.
        //   1차: "서버가 정규화한다"는 전제를 목으로 만들어 통과시켰다 — 확인 없이.
        //   2차: 실측한다며 `116238`·`116238 CHSJ`를 던졌는데 **둘 다 정규화해도 그대로인
        //        값**이라 "되울린다"로 잘못 읽고, 주석을 반대로 고쳤다.
        // 목이 문제였던 게 아니라 **되울림과 항등을 구분하는 입력을 안 골랐던 것**이다.
        // 소문자를 한 번 넣어 봤으면 1차에 갈렸다.
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
    fun `소재 기호는 안 잘린다 — 붙이고 빼는 데 따라 다른 키다`() {
        // 실측: `116238 CHSJ` → refKey `116238 CHSJ` · 표본 1건 / `116238` → 0건.
        // 정규화는 대소문자·공백까지만 손대고 **꼬리 기호는 남긴다** — 자르면 스틸과 금이
        // 한 중앙값에 섞이기 때문이다(watch-data RefNormalizer KDoc의 실측 근거).
        // 화면 안내가 "적은 그대로 찾는다"고 말하는 근거가 이것이다.
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
