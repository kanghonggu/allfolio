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
 * S030이 **워크플로우가 정한 일자**를 스냅샷 기록까지 흘려보내는지 못 박는다.
 *
 * PerformanceSnapshotDateTest가 잎(record()가 받은 날짜를 쓴다)을 잡고 있지만, 그 위
 * 경로는 아무 테스트도 없었다 — NavSnapshotAction이 ctx.ymd 대신 LocalDate.now()를 쓰면
 * 운영 컨테이너(UTC)에서 자정 KST 실행이 전날에 앉아 wf_job_log.ymd와 performance_daily.date가
 * 영원히 어긋난다. 지금까지 이걸 막고 있던 건 주석 두 줄뿐이었다.
 *
 * **고정 날짜를 쓰는 이유**: 개발 머신이 KST라 ctx.ymd에 오늘을 넣으면 ctx를 무시하고
 * LocalDate.now()를 부르는 구현도 그대로 통과한다. 오늘일 수 없는 날짜여야 변이가 잡힌다.
 *
 * 어떤 일자를 넘겨야 옳은지(ctx.ymd냐 ctx.ymd.minusDays(1)이냐)는 여기서 정하지 않는다 —
 * 이 테스트는 지금 동작이 "받은 날짜를 그대로 흘려보낸다"임을 고정할 뿐이다.
 */
class NavSnapshotActionTest {

    private val scheduler: DailyNavScheduler = mock(DailyNavScheduler::class.java)

    /** 오늘일 수 없는 과거 날짜 — 아래 가드가 이 전제를 매 실행 확인한다. */
    private val ymd = LocalDate.of(2024, 2, 29)

    @Test
    fun `워크플로우가 정한 일자를 그대로 스냅샷 기록에 넘긴다`() {
        // 이 가드가 없으면 위 날짜가 언젠가 오늘과 겹치도록 바뀌었을 때
        // 테스트가 아무것도 안 보면서 초록으로 남는다.
        assertThat(ymd).isNotEqualTo(LocalDate.now())

        `when`(scheduler.recordDailySnapshots(ymd)).thenReturn(7)

        NavSnapshotAction(scheduler).execute(WfContext(ymd))

        // capture()는 null을 돌려주는데 DailyNavScheduler는 Kotlin 파이널 클래스라
        // 원본 바이트코드의 non-null 파라미터 검사가 남는다. 엘비스로 채우면 캡처는 그대로 된다.
        val captured = ArgumentCaptor.forClass(LocalDate::class.java)
        verify(scheduler).recordDailySnapshots(captured.capture() ?: LocalDate.EPOCH)
        assertThat(captured.value).isEqualTo(ymd)
    }

    // 이 문자열이 그대로 wf_job_log.remark에 앉는다 — 운영자가 "S030이 돌았는데 몇 명 찍혔나"에
    // 답할 수 있는 유일한 신호다. 건수가 빠지면 성공/무실행을 구분할 방법이 없어진다.
    @Test
    fun `요약에 기록한 사용자 수가 실린다`() {
        `when`(scheduler.recordDailySnapshots(ymd)).thenReturn(7)

        val result = NavSnapshotAction(scheduler).execute(WfContext(ymd))

        assertThat(result.summary).isEqualTo("snapshots=7")
    }

    // 0건도 정상 종료다(자산 있는 사용자가 없는 경우). 요약이 비어버리면 잡 로그에서
    // "안 돌았다"와 구분되지 않는다.
    @Test
    fun `한 건도 없으면 0으로 요약한다`() {
        `when`(scheduler.recordDailySnapshots(ymd)).thenReturn(0)

        val result = NavSnapshotAction(scheduler).execute(WfContext(ymd))

        assertThat(result.summary).isEqualTo("snapshots=0")
    }
}
