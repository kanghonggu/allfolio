package com.allfolio.api.market

import com.allfolio.realasset.watch.RefSearchOutcome
import com.allfolio.realasset.watch.WatchRefCandidateResponse
import com.allfolio.realasset.watch.WatchRefSearchClient
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.mockito.ArgumentMatchers.anyInt
import org.mockito.ArgumentMatchers.anyString
import org.mockito.ArgumentMatchers.isNull
import org.mockito.Mockito.mock
import org.mockito.Mockito.never
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when`

class WatchRefSearchControllerTest {

    private val client = mock(WatchRefSearchClient::class.java)
    private val controller = WatchRefSearchController(client)

    @Test
    fun `후보를 고르면 넘길 키는 refKey다 — 표시용 ref가 아니다`() {
        // #51 계약: refKey를 그대로 /api/valuation?ref= 에 넘긴다. ref는 원문(소문자·괄호 주석 포함 가능)
        `when`(client.search("daytona", null, 20)).thenReturn(
            RefSearchOutcome.Found(
                listOf(
                    WatchRefCandidateResponse(
                        ref = "126500ln",
                        refKey = "126500LN",
                        brand = "Rolex",
                        modelTitle = "Cosmograph Daytona",
                        officialPriceKrw = 23_000_000,
                    ),
                ),
            ),
        )

        val res = controller.search("  daytona ", null, 20)

        assertThat(res.statusCode.value()).isEqualTo(200)
        val body = res.body!!
        assertThat(body.status).isEqualTo(WatchRefSearchStatus.OK)
        assertThat(body.candidates).hasSize(1)
        assertThat(body.candidates[0].refKey).isEqualTo("126500LN")
        assertThat(body.candidates[0].ref).isEqualTo("126500ln")
        assertThat(body.candidates[0].modelTitle).isEqualTo("Cosmograph Daytona")
    }

    @Test
    fun `후보가 없으면 OK에 빈 목록이다`() {
        `when`(client.search("데이토나", null, 20)).thenReturn(RefSearchOutcome.Found(emptyList()))

        val body = controller.search("데이토나", null, 20).body!!

        assertThat(body.status).isEqualTo(WatchRefSearchStatus.OK)
        assertThat(body.candidates).isEmpty()
    }

    @Test
    fun `상류 실패는 5xx가 아니라 UNAVAILABLE이다 — 빈 목록과 다른 답이다`() {
        // '없다'와 '못 찾았다'를 섞으면 상류가 죽어 있는 동안 "그런 시계가 없다"는 거짓 답이 나간다.
        `when`(client.search("daytona", null, 20)).thenReturn(RefSearchOutcome.Unavailable("TimeoutException"))

        val res = controller.search("daytona", null, 20)

        assertThat(res.statusCode.value()).isEqualTo(200)
        assertThat(res.body!!.status).isEqualTo(WatchRefSearchStatus.UNAVAILABLE)
        assertThat(res.body!!.candidates).isEmpty()
    }

    @Test
    fun `한 글자는 상류를 부르지 않고 빈 목록이다`() {
        // 자동완성 첫 타자다. 상류도 [] 를 주므로 호출을 아낀다(무료 티어).
        val body = controller.search(" d ", null, 20).body!!

        assertThat(body.status).isEqualTo(WatchRefSearchStatus.OK)
        assertThat(body.candidates).isEmpty()
        verify(client, never()).search(anyString(), isNull(), anyInt())
    }

    @Test
    fun `64자를 넘으면 상류 400을 받지 않고 후보 없음으로 답한다`() {
        val body = controller.search("x".repeat(65), null, 20).body!!

        assertThat(body.status).isEqualTo(WatchRefSearchStatus.OK)
        assertThat(body.candidates).isEmpty()
        verify(client, never()).search(anyString(), isNull(), anyInt())
    }

    @Test
    fun `brand는 공백을 떼고 빈 값이면 안 보낸다 — size는 50으로 자른다`() {
        `when`(client.search("daytona", "Rolex", 50)).thenReturn(RefSearchOutcome.Found(emptyList()))
        `when`(client.search("daytona", null, 1)).thenReturn(RefSearchOutcome.Found(emptyList()))

        controller.search("daytona", "  Rolex ", 500)
        controller.search("daytona", "   ", 0)

        verify(client).search("daytona", "Rolex", 50)
        verify(client).search("daytona", null, 1)
    }
}
