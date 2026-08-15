package com.allfolio.closing

import com.allfolio.unifiedasset.application.usecase.DailyNavScheduler
import com.allfolio.workflow.application.WfContext
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.mockito.ArgumentCaptor
import org.mockito.Mockito.mock
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when`
import java.time.LocalDate

/**
 * S030이 **직전 영업일**을 스냅샷 기록까지 흘려보내는지 못 박는다.
 *
 * PerformanceSnapshotDateTest가 잎(record()가 받은 날짜를 쓴다)을 잡고 있지만, 그 위
 * 경로는 아무 테스트도 없었다 — NavSnapshotAction이 ctx.ymd 대신 LocalDate.now()를 쓰면
 * 운영 컨테이너(UTC)에서 자정 KST 실행이 전날에 앉아 wf_job_log.ymd와 performance_daily.date가
 * 영원히 어긋난다. 지금까지 이걸 막고 있던 건 주석 두 줄뿐이었다.
 *
 * **고정 날짜를 쓰는 이유**: 개발 머신이 KST라 ctx.ymd에 오늘을 넣으면 ctx를 무시하고
 * LocalDate.now()를 부르는 구현도 그대로 통과한다. 오늘일 수 없는 날짜여야 변이가 잡힌다.
 *
 **ctx.ymd가 아니라 ctx.ymd − 1일인 이유**는 NavSnapshotAction KDoc에 있다. 요약하면:
 * 워크플로우는 KST 자정에 뜨고 ctx.ymd는 실행일이라, 그 시점 자산은 직전 영업일이 끝난 값이다.
 * 실행일로 라벨하면 벤치마크 비교가 하루 어긋나는데 **as-of 조회라 null도 구멍도 안 생겨
 * 화면에 신호가 전혀 안 뜬다.** 그래서 이 방향은 테스트로 잡아야만 한다.
 */
class NavSnapshotActionTest {

    private val scheduler: DailyNavScheduler = mock(DailyNavScheduler::class.java)

    /** 실행일 — 오늘일 수 없는 과거 날짜. 아래 가드가 이 전제를 매 실행 확인한다. */
    private val ymd = LocalDate.of(2024, 3, 1)

    /** 기록되어야 할 날짜 — 실행일의 직전일 */
    private val expected = LocalDate.of(2024, 2, 29)

    @Test
    fun `실행일이 아니라 직전일로 기록한다`() {
        // 이 가드가 없으면 위 날짜가 언젠가 오늘과 겹치도록 바뀌었을 때
        // 테스트가 아무것도 안 보면서 초록으로 남는다.
        assertThat(ymd).isNotEqualTo(LocalDate.now())

        `when`(scheduler.recordDailySnapshots(expected)).thenReturn(7)

        NavSnapshotAction(scheduler).execute(WfContext(ymd))

        // capture()는 null을 돌려주는데 DailyNavScheduler는 Kotlin 파이널 클래스라
        // 원본 바이트코드의 non-null 파라미터 검사가 남는다. 엘비스로 채우면 캡처는 그대로 된다.
        val captured = ArgumentCaptor.forClass(LocalDate::class.java)
        verify(scheduler).recordDailySnapshots(captured.capture() ?: LocalDate.EPOCH)
        assertThat(captured.value).isEqualTo(expected)
        // 윤일을 고른 이유: minusDays(1)을 minusMonths/minusYears 등으로 잘못 바꿔도
        // 2024-03-01 → 2024-02-29는 오직 하루 빼기에서만 나온다
        assertThat(captured.value).isNotEqualTo(ymd)
    }

    // 이 문자열이 그대로 wf_job_log.remark에 앉는다 — 운영자가 "S030이 돌았는데 몇 명 찍혔나"에
    // 답할 수 있는 유일한 신호다. 건수가 빠지면 성공/무실행을 구분할 방법이 없어진다.
    @Test
    fun `요약에 기록한 사용자 수가 실린다`() {
        `when`(scheduler.recordDailySnapshots(expected)).thenReturn(7)

        val result = NavSnapshotAction(scheduler).execute(WfContext(ymd))

        assertThat(result.summary).isEqualTo("snapshots=7")
    }

    // 0건도 정상 종료다(자산 있는 사용자가 없는 경우). 요약이 비어버리면 잡 로그에서
    // "안 돌았다"와 구분되지 않는다.
    @Test
    fun `한 건도 없으면 0으로 요약한다`() {
        `when`(scheduler.recordDailySnapshots(expected)).thenReturn(0)

        val result = NavSnapshotAction(scheduler).execute(WfContext(ymd))

        assertThat(result.summary).isEqualTo("snapshots=0")
    }
}
