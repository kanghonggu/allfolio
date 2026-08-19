package com.allfolio.api.admin

import com.allfolio.realasset.RealAssetValuationService
import com.allfolio.realasset.RealAssetValuationSummary
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import org.mockito.ArgumentCaptor
import org.mockito.ArgumentMatchers.any
import org.mockito.Mockito.mock
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when`
import org.springframework.http.HttpStatus
import org.springframework.web.server.ResponseStatusException
import java.time.LocalDateTime
import java.time.LocalDate
import java.time.ZoneId
import java.util.TimeZone

/**
 * **상태 코드 규칙이 시세 수집 어드민과 두 군데서 갈린다.** 그 두 지점이 이 파일의 존재 이유다:
 * 대상 0건이 정상이고(사용자가 아직 안 등록), 전량 실패가 502가 아니라 500이다(상류가 없다).
 * 형제 컨트롤러를 복사해 오면 둘 다 반대로 들어오기 쉬워서 여기서 못 박는다.
 */
class RealAssetValuationAdminControllerTest {

    private val service: RealAssetValuationService = mock(RealAssetValuationService::class.java)
    private val controller = RealAssetValuationAdminController(service)

    /**
     * **0건은 실패가 아니다 — 원자재 어드민과 정확히 반대다.** 거기서는 대상 0건이 설정 실수라
     * 500이지만, 평가 대상은 사용자가 등록한 자산이라 0건이 정상이다. 이걸 실패로 내면
     * 배포 첫날부터 아무도 실물자산을 안 넣은 동안 매일 빨간 잡이 뜨고, 그러면 아무도 안 본다.
     */
    @Test
    fun `대상 자산이 없어도 200이다`() {
        stub(summary(requested = 0, valued = 0))

        val response = controller.valuate(null)

        assertThat(response.statusCode).isEqualTo(HttpStatus.OK)
        assertThat(response.body?.requested).isZero()
    }

    /**
     * **502가 아니라 500이다.** 이 배치는 상류를 안 부르고 우리 DB만 읽는다 — 전량 실패의
     * 원인은 마이그레이션 미적용이거나 코드 오류이지 외부 장애가 아니다. 502로 내면
     * 운영자가 멀쩡한 공공데이터포털 상태 페이지를 확인하러 간다.
     */
    @Test
    fun `전량 실패면 500이고 사유를 싣는다`() {
        stub(summary(requested = 2, valued = 0, failed = 2, failures = listOf("a: 릴레이션 없음", "b: 릴레이션 없음")))

        val thrown = assertThrows(ResponseStatusException::class.java) { controller.valuate(null) }

        assertThat(thrown.statusCode).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR)
        assertThat(thrown.reason).contains("릴레이션 없음")
    }

    /**
     * 전부 건너뛴 것은 실패가 아니다 — 연휴라 시세가 없거나 아직 어댑터가 없는 유형만
     * 등록된 것이라 둘 다 정상이다. `skipped`가 이름을 대므로 조용하지도 않다.
     * **이 분기를 위 전량 실패와 합치지 말 것** — 합치면 연휴마다 잡이 빨개진다.
     */
    @Test
    fun `전부 건너뛰었어도 실패가 아니면 200이다`() {
        stub(summary(requested = 1, valued = 0, skipped = listOf("a: 산출 불가 (GOLD)")))

        val response = controller.valuate(null)

        assertThat(response.statusCode).isEqualTo(HttpStatus.OK)
        assertThat(response.body?.skipped).hasSize(1)
    }

    /** 부분 실패는 200이다. 자산 하나가 흔들렸다고 매일 잡이 실패로 보이면 아무도 안 본다 */
    @Test
    fun `부분 실패는 200이다`() {
        stub(summary(requested = 2, valued = 1, failed = 1, failures = listOf("a: 터짐")))

        assertThat(controller.valuate(null).statusCode).isEqualTo(HttpStatus.OK)
    }

    /**
     * **Render 컨테이너는 UTC다.** `LocalDate.now()`를 쓰면 크론이 밀려 자정을 넘긴 날
     * 평가일이 하루 전으로 밀리고, 그러면 어제 스냅샷을 오늘 값으로 덮는다.
     * 컨테이너를 UTC로 고정해 놓고 KST 날짜가 나오는지 본다.
     */
    @Test
    fun `평가일을 안 주면 KST 오늘로 잡는다`() {
        val original = TimeZone.getDefault()
        TimeZone.setDefault(TimeZone.getTimeZone("UTC"))
        try {
            stub(summary(requested = 1, valued = 1))

            controller.valuate(null)

            val captor = ArgumentCaptor.forClass(LocalDate::class.java)
            verify(service).valuate(captor.capture() ?: LocalDate.EPOCH, any(LocalDateTime::class.java) ?: LocalDateTime.MIN)
            assertThat(captor.value).isEqualTo(LocalDate.now(ZoneId.of("Asia/Seoul")))
        } finally {
            TimeZone.setDefault(original)
        }
    }

    /** 백필·재실행용. 준 날짜를 그대로 쓴다 */
    @Test
    fun `평가일을 주면 그대로 쓴다`() {
        stub(summary(requested = 1, valued = 1))

        controller.valuate(LocalDate.of(2026, 8, 14))

        val captor = ArgumentCaptor.forClass(LocalDate::class.java)
        verify(service).valuate(captor.capture() ?: LocalDate.EPOCH, any(LocalDateTime::class.java) ?: LocalDateTime.MIN)
        assertThat(captor.value).isEqualTo(LocalDate.of(2026, 8, 14))
    }

    private fun stub(summary: RealAssetValuationSummary) {
        // **`?: 기본값`이 붙는 이유**: Mockito의 any()는 null을 돌려주는데 코틀린의 non-null
        // 파라미터가 그 자리에서 널 검사로 터진다. 형제 `CommodityAdminControllerTest`가
        // 같은 자리에 같은 우회를 쓴다.
        `when`(
            service.valuate(
                any(LocalDate::class.java) ?: LocalDate.EPOCH,
                any(LocalDateTime::class.java) ?: LocalDateTime.MIN,
            ),
        ).thenReturn(summary)
    }

    private fun summary(
        requested: Int,
        valued: Int,
        failed: Int = 0,
        failures: List<String> = emptyList(),
        skipped: List<String> = emptyList(),
    ) = RealAssetValuationSummary(
        valuedOn = LocalDate.of(2026, 8, 18),
        requested = requested,
        updated = valued,
        unchanged = 0,
        skipped = skipped,
        failed = failed,
        failures = failures,
    )
}
