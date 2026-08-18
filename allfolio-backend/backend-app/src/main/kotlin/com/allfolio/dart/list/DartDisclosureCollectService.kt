package com.allfolio.dart.list

import com.allfolio.dart.DartReportName
import com.allfolio.dart.DartWhitelist
import com.allfolio.unifiedasset.infrastructure.entity.DartCollectionRunEntity
import com.allfolio.unifiedasset.infrastructure.jpa.DartCollectionRunJpaRepository
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import org.springframework.stereotype.Service
import java.time.LocalDate
import java.time.LocalDateTime

/**
 * `DartListClient`를 서비스에서 갈아 끼우기 위한 좁은 포트. 서비스가 `DartListClient`(HTTP·
 * WebClient 의존)를 직접 받으면 테스트가 WebClient를 가짜로 세워야 한다 — `Store`·`RunLog`와
 * 같은 이유로 인터페이스를 하나 더 둔다.
 */
interface ListPort {
    fun fetchPage(bgnDe: LocalDate, endDe: LocalDate, pageNo: Int): DartListPage
}

/** 운영 배선. `DartListClient`를 [ListPort]로 감싸기만 한다 */
@Component
class DartListPortAdapter(private val client: DartListClient) : ListPort {
    override fun fetchPage(bgnDe: LocalDate, endDe: LocalDate, pageNo: Int) =
        client.fetchPage(bgnDe, endDe, pageNo)
}

/**
 * 수집 한 번의 결과.
 *
 * @param newRceptNos 이번 실행에서 새로 들어간 접수번호. **Task 11의 `elestock` 호출이 이것만
 *                     소비한다** — 델타 밖의 행(이미 있던 건)까지 다시 부르면 같은 임원 소유변동을
 *                     매번 호출하게 된다.
 * @param emptyResult `status 013`(공휴일 등)을 그대로 실었는지. 이때도 `status`는 `SUCCESS`다.
 */
data class DartCollectSummary(
    val bgnDe: LocalDate,
    val endDe: LocalDate,
    val pagesFetched: Int,
    val apiCalls: Int,
    val newCount: Int,
    val emptyResult: Boolean,
    val newRceptNos: List<String>,
)

/**
 * `list.json`을 D-1~D 구간으로 전 페이지 수집해 적재하는 배치 서비스 (D1 Task 8).
 *
 * 지금까지의 조각을 처음으로 엮는다 — `DartReportName.normalize`(Task 3)로 정규화하고
 * `DartWhitelist.tierOf`/`isMaterial`(Task 4)로 Tier를 매긴 뒤 `JdbcDisclosureStore`
 * (Task 7, [Store]로 받는다)에 델타 확보를 맡긴다. 클라이언트는 [ListPort]([DartListClient]
 * 를 감싼 어댑터, Task 6)로 받는다.
 *
 * **조기중단을 넣지 말 것.** `pageNo=1`부터 `totalPage`까지 매 실행 전 페이지를 순회한다.
 * "정렬이 항상 접수순"이라는 검증 불가능한 가정이 있어야 중간에 멈출 수 있는데, DART가 그걸
 * 보장한다는 근거가 없다. 아껴 봐야 하루 수십 콜 차이고(한도 20,000, 실측 최다일 46페이지),
 * 매 실행이 D-1을 다시 훑으므로("델타 확보"는 [Store]가 `ON CONFLICT DO NOTHING`으로 흡수)
 * 스윕은 이미 내장돼 있다.
 *
 * **걸러낸 건도 저장한다** (설계 원칙 4). `is_material=false`인 행도 전부 적재한다 —
 * 무엇을 걸렀는지 되짚을 수 없으면 화이트리스트 튜닝(S13)이 불가능하다.
 *
 * **공휴일(`emptyResult`)은 실패가 아니다.** `status=SUCCESS`·`new_count=0`으로 정상 종료한다.
 * 실패로 기록하면 대체공휴일마다 배치가 빨갛게 된다 — 근거는 [DartListClient]의 KDoc.
 *
 * **실패하면 `dart_collection_run`에 `FAILED`+`error_msg`를 남기고 예외를 다시 던진다.**
 * 조용히 삼키면 스케줄러가 "정상 종료"로 읽고, 다시 던지지 않으면 어드민이 실패를 못 본다.
 * 실패 시에도 그때까지의 `pagesFetched`·`apiCalls`는 남긴다 — 그래야 사고 조사에서
 * "바로 죽었다"와 "40페이지 돌다 죽었다"를 가를 수 있고, OpenDART 쿼터가 실제로 얼마나
 * 소비됐는지도 알 수 있다.
 *
 * **감사 로그 저장 실패가 이미 커밋된 수집 결과를 삼키면 안 된다.** `store.insertIgnoringConflicts`는
 * `@Transactional`이라 이 메서드로 반환된 시점에 이미 커밋돼 있다([JdbcDisclosureStore] KDoc의
 * "트랜잭션 경계" 절). 그 뒤 `runLog.save`가 Neon 콜드 스타트·연결 끊김 등으로 던지면, 여기서
 * 그대로 전파해 `collect()` 자체가 예외로 끝날 경우 [DartCollectSummary]가 호출자에게 못
 * 돌아가고 `newRceptNos`(델타)가 사라진다. `dart_disclosure` 행은 이미 들어가 있으므로 다음
 * 재수집은 `ON CONFLICT DO NOTHING`이 그 행을 조용히 걸러 **그 델타가 영원히 어떤 실행에도
 * 안 잡힌다** — Task 11의 `elestock` 호출이 델타로만 구동되므로 임원 소유변동이 영영 안
 * 채워진다. 그래서 [saveRunLog]가 저장 실패를 삼킨다: 감사 행을 못 남기는 것과 델타를 잃는
 * 것 중 후자가 훨씬 나쁘다 — 전자는 `dart_disclosure`를 직접 봐서라도 사후에 되짚을 수 있지만
 * 후자는 조용히 영구적이다.
 */
@Service
class DartDisclosureCollectService(
    private val client: ListPort,
    private val store: Store,
    private val runLog: RunLog,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    /**
     * 저장에 필요한 것만 추린 좁은 포트. [JdbcDisclosureStore]가 이걸 구현한다 —
     * `@Transactional`·청크 배칭은 그 클래스 KDoc을 볼 것.
     */
    interface Store {
        fun insertIgnoringConflicts(rows: List<DisclosureInsert>, collectedAt: LocalDateTime): List<String>
    }

    /**
     * 실행 기록 저장. `collect()`가 분기(성공/실패)당 **정확히 한 번만** 부른다 — 시작 시점에
     * 별도로 "진행 중" 행을 만들지 않는다.
     *
     * **강제 종료(OOM·컨테이너 재시작 등, `catch (Exception)`이 못 잡는 경우)면 그 실행에
     * 대응하는 행이 아예 안 생긴다.** "시작됐는데 안 끝난 행"으로 그 상황을 감지할 방법이
     * 이 설계에는 없다 — 그런 감지가 필요해지면 시작 시점 별도 `save()` + 종료 시 갱신으로
     * 바꿔야 한다.
     */
    interface RunLog {
        fun save(run: DartCollectionRunEntity)
    }

    fun collect(bgnDe: LocalDate, endDe: LocalDate, now: LocalDateTime): DartCollectSummary {
        // 시작 시점에 행을 만들어 둔다 — 실패 분기에서 같은 인스턴스를 FAILED로 채워 저장한다.
        // status는 일단 FAILED로 시작한다: 성공 경로가 끝까지 못 가고 예외가 새면(버그) 이 값이
        // 남아 "성공"으로 잘못 보고되는 쪽보다 "실패"로 잘못 보고되는 쪽이 안전하다
        val run = DartCollectionRunEntity(
            runAt = now, bgnDe = bgnDe, endDe = endDe,
            status = "FAILED", errorMsg = null, finishedAt = null,
        )

        // catch 블록에서도 읽어야 하므로 try 밖에서 선언한다 — 5페이지째에서 죽어도
        // "몇 페이지·몇 콜까지 갔었는지"가 dart_collection_run에 남아야 사고 조사가 된다
        var pageNo = 1
        var apiCalls = 0

        try {
            val collected = mutableListOf<DartListRow>()
            var totalPage = 1
            var emptyResult = false

            while (pageNo <= totalPage) {
                val page = client.fetchPage(bgnDe, endDe, pageNo)
                apiCalls++
                if (page.emptyResult) {
                    // 공휴일 등 — 정상 종료. 페이지 루프를 더 돌 이유가 없다(totalPage=0)
                    emptyResult = true
                    break
                }
                collected += page.rows
                totalPage = page.totalPage
                pageNo++
            }

            // 화이트리스트 판정을 적재와 함께 한다: report_nm → normalize → tierOf → isMaterial.
            // 걸러낸 건(is_material=false)도 여기서 함께 DisclosureInsert가 되어 store로 간다 —
            // 별도 분기로 빼서 저장을 생략하지 않는다
            val delta = store.insertIgnoringConflicts(collected.map(::toInsert), now)

            run.pagesFetched = if (emptyResult) 0 else pageNo - 1
            run.apiCalls = apiCalls
            run.newCount = delta.size
            run.status = "SUCCESS"
            run.finishedAt = now
            // 여기서 던져도 삼킨다 — 근거는 클래스 KDoc "감사 로그 저장 실패가 이미 커밋된
            // 수집 결과를 삼키면 안 된다" 절. delta는 이미 커밋됐으니 반드시 아래 return까지 간다
            saveRunLog(run)

            log.info(
                "[DART] 수집 완료 {}~{} pages={} apiCalls={} new={} emptyResult={}",
                bgnDe, endDe, run.pagesFetched, apiCalls, delta.size, emptyResult,
            )

            return DartCollectSummary(
                bgnDe = bgnDe, endDe = endDe,
                pagesFetched = run.pagesFetched, apiCalls = apiCalls,
                newCount = delta.size, emptyResult = emptyResult, newRceptNos = delta,
            )
        } catch (e: Exception) {
            // 실패는 조용히 삼키지 않는다 — FAILED를 남기고 예외를 다시 던져 스케줄러가 빨갛게
            // 볼 수 있게 한다. 여기서 삼키면 "그날 공시가 없었다"와 "호출이 죽었다"가 구분 안 된다.
            // pageNo·apiCalls는 try 밖 선언 덕에 실패 직전까지의 진행이 그대로 남는다
            run.pagesFetched = pageNo - 1
            run.apiCalls = apiCalls
            run.status = "FAILED"
            run.errorMsg = e.message
            run.finishedAt = now
            saveRunLog(run)
            log.warn("[DART] 수집 실패 {}~{} pages={} apiCalls={} reason={}", bgnDe, endDe, run.pagesFetched, apiCalls, e.message)
            // saveRunLog가 삼킨 것과 무관하게 원래 예외 e를 그대로 올린다 — 감사 로그 저장이
            // 실패했다고 진짜 실패 사유가 다른 예외로 덮이면 안 된다
            throw e
        }
    }

    /**
     * `runLog.save`를 감싸 저장 실패를 삼킨다. **의도적이다** — 근거는 클래스 KDoc
     * "감사 로그 저장 실패가 이미 커밋된 수집 결과를 삼키면 안 된다" 절.
     * 여기서 예외가 새 나가면 성공 경로에서는 이미 커밋된 델타를 호출자에게 영원히 못
     * 돌려주고(Task 11의 `elestock` 호출이 조용히 빠진다), 실패 경로에서는 원래 실패 사유(`e`)가
     * 감사 로그 저장 실패로 덮인다. 어느 쪽도 감사 행 하나 못 남기는 것보다 훨씬 비싸다.
     */
    private fun saveRunLog(run: DartCollectionRunEntity) {
        try {
            runLog.save(run)
        } catch (e: Exception) {
            log.error(
                "[DART] 실행 기록 저장 실패 — status={} pagesFetched={} apiCalls={} newCount={} reason={}",
                run.status, run.pagesFetched, run.apiCalls, run.newCount, e.message, e,
            )
        }
    }

    /** `report_nm` → 정규화 → Tier 판정까지 한 곳에서 한다. 순서를 바꾸면 접두어가 안 떨어진 채로 tierOf에 들어간다 */
    private fun toInsert(r: DartListRow): DisclosureInsert {
        val norm = DartReportName.normalize(r.reportNm)
        val tier = DartWhitelist.tierOf(norm)
        return DisclosureInsert(
            rceptNo = r.rceptNo, corpCode = r.corpCode, corpName = r.corpName,
            stockCode = r.stockCode, corpCls = r.corpCls,
            reportNm = r.reportNm, reportNmNorm = norm,
            rceptDt = r.rceptDt, flrNm = r.flrNm, rm = r.rm,
            isMaterial = DartWhitelist.isMaterial(tier),
            materialTier = tier,
            // 접두어 유무는 normalize와 별개로 원문에서 직접 판정한다 — hasCorrectionPrefix가
            // normalize의 반복 제거 루프와 같은 조건으로 첫 겹만 본다(DartReportName KDoc 참고)
            isCorrection = DartReportName.hasCorrectionPrefix(r.reportNm),
        )
    }
}

/** 운영 배선. [DartCollectionRunJpaRepository]를 [DartDisclosureCollectService.RunLog]로 감싼다 */
@Component
class JpaDartRunLog(
    private val repository: DartCollectionRunJpaRepository,
) : DartDisclosureCollectService.RunLog {
    override fun save(run: DartCollectionRunEntity) {
        repository.save(run)
    }
}
