package com.allfolio.dart.insider

import com.allfolio.dart.DartWhitelist
import com.allfolio.unifiedasset.infrastructure.entity.DartDisclosureEntity
import com.allfolio.unifiedasset.infrastructure.entity.DartInsiderTradeEntity
import com.allfolio.unifiedasset.infrastructure.jpa.DartDisclosureJpaRepository
import com.allfolio.unifiedasset.infrastructure.jpa.DartInsiderTradeJpaRepository
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import org.springframework.stereotype.Service
import java.time.LocalDateTime

/**
 * `DartElestockClient`를 서비스에서 갈아 끼우기 위한 좁은 포트. `DartListPortAdapter`
 * (`dart/list/DartDisclosureCollectService.kt`)와 같은 이유로 인터페이스를 하나 더 둔다 — 서비스가
 * `DartElestockClient`(HTTP·WebClient 의존)를 직접 받으면 테스트가 WebClient를 가짜로
 * 세워야 한다.
 */
interface ElestockPort {
    fun fetch(corpCode: String): List<ElestockRow>
}

/** 운영 배선. `DartElestockClient`를 [ElestockPort]로 감싸기만 한다 */
@Component
class ElestockPortAdapter(private val client: DartElestockClient) : ElestockPort {
    override fun fetch(corpCode: String) = client.fetch(corpCode)
}

/**
 * 수집 한 번의 결과.
 *
 * @param calls elestock을 부른 회사 수(중복 제거 후). Tier 4 트리거가 없으면 0이다.
 * @param inserted 실제로 새로 저장된 소유변동 행 수. 회사 전체 이력이 아니라 델타 필터·
 *                 기존 키 회피를 통과한 건만 센다.
 * @param failures 회사별 실패 사유. `corp_code=...: 메시지` 형식으로, 한 회사가 실패해도
 *                 나머지는 계속 진행하고 여기 쌓아 요약으로 올린다.
 */
data class InsiderCollectSummary(
    val calls: Int,
    val inserted: Int,
    val failures: List<String>,
)

/**
 * 델타(Task 8이 저장한 공시 중 이번 실행에서 새로 들어간 것) 중 Tier 4(임원·주요주주 특정증권등
 * 소유상황보고서) 공시의 회사만 `elestock`을 불러 임원 소유변동을 적재한다 (D1 Task 11).
 *
 * **응답 중 델타에 있는 `rcept_no`만 적재한다 — 이 필터가 이 서비스의 핵심이다.**
 * `elestock`은 기간 파라미터가 없어 회사 전체 이력(약 2년, 실측 최대 3,395행 삼성전자)을
 * 통째로 준다([DartElestockClient] KDoc). 걸러내지 않으면 같은 회사에 Tier 4 공시가 다시
 * 뜰 때마다 이미 저장된 2년치 이력을 재삽입 시도하게 된다 — `uq_insider` 회피(아래)가 결과는
 * 막아 주지만, 그 판정을 위해 매번 회사 전체 이력을 스캔하는 비용이 남는다. 델타 필터가
 * 먼저 걸려 있어야 이 스캔 자체가 "이번에 새로 온 rcept_no 몇 건"으로 줄어든다.
 *
 * **회사 중복 제거.** 같은 회사에 Tier 4 공시가 델타 안에 둘 이상 있어도 `elestock`은 회사당
 * 한 번만 부른다 — 응답이 어차피 회사 전체 이력이라 두 번 불러도 새로 얻는 정보가 없다.
 *
 * **`uq_insider (rcept_no, repror)` 중복 회피.** 이미 저장된 조합은 다시 넣지 않는다. 델타
 * 필터만으로는 부족하다 — 같은 델타 배치 안에서(또는 재실행에서) 같은 `(rcept_no, repror)`가
 * 다시 보일 수 있어 DB 제약 위반으로 배치 전체가 죽는 것을 여기서 미리 막는다.
 *
 * **공시의 `stock_code`를 소유변동 행에 물려준다.** `elestock` 응답에는 `stock_code`가 없다
 * ([ElestockRow] KDoc) — 트리거가 된 [DartDisclosureEntity]에서 가져온다.
 *
 * **한 회사가 실패해도 나머지는 진행한다.** 사유는 [InsiderCollectSummary.failures]에 담는다.
 * 공시 수집(TX1, Task 8 [com.allfolio.dart.list.DartDisclosureCollectService])은 이미
 * 커밋됐으므로 여기서 예외를 올려도 그쪽은 롤백되지 않는다 — 오히려 여기서 예외를 전파하면
 * 아직 처리 안 된 나머지 회사들의 소유변동만 통째로 못 들어간다.
 *
 * **델타가 비면 `elestock`을 아예 안 부른다.** Task 8이 공휴일 등으로 새 공시가 없으면
 * `newRceptNos`가 빈 리스트로 온다 — 이때 `findDisclosures(emptyList())`조차 부르지 않고
 * 바로 반환한다.
 *
 * **부분 커밋 판단 — `@Transactional`로 묶지 않는다.** `saveAll`은 회사마다(Tier 4 트리거
 * 회사 수만큼) 호출된다. 중간에(예: 3번째 회사에서) 죽으면 앞 두 회사분은 이미 커밋돼 있고
 * 뒤는 안 들어간다. Task 8의 `JdbcDisclosureStore`와 달리 **이건 재실행으로 저절로 복구된다**:
 * - 이 서비스가 소비하는 델타(`deltaRceptNos`)는 Task 8이 그 실행에서 **새로 저장한**
 *   `rcept_no` 목록이지, "아직 elestock 처리가 안 된 Tier 4 공시" 목록이 아니다. 따라서
 *   다음 배치 실행에서 이 서비스가 다시 불릴 때 전달되는 델타는 그사이 새로 들어온 공시일
 *   뿐, 실패했던 회사의 트리거 `rcept_no`가 다시 오지는 않는다 — 언뜻 "재실행해도 못 채운다"로
 *   보일 수 있다.
 * - 하지만 `elestock`이 **회사 전체 이력**을 준다는 성질이 이 우려를 무력화한다. 실패했던
 *   회사가 이후 또 다른 Tier 4 공시를 내면(등기임원 변동 보고는 반복적으로 발생), 그 다음
 *   델타에 새 `rcept_no`가 잡히고 이 서비스가 그 회사를 다시 부른다. 이번엔 회사 **전체**
 *   이력이 오므로, 지난번 실패로 못 들어갔던 옛 `rcept_no`까지 함께 응답에 실린다. 다만
 *   그 옛 `rcept_no`는 그때의 델타 집합(`deltaSet`)엔 없다 — **이번** 델타 필터를 그대로
 *   적용하면 걸러져 버린다.
 * - 그래서 실패한 회사의 결손은 "다음 Tier 4 공시가 그 회사에서 나올 때 자동 복구"가 아니라
 *   **영구 결손**이다. 이는 이 서비스의 설계 범위를 벗어난 문제로 판단한다: 델타 기반 적재는
 *   Task 8부터 이어지는 전제(재수집 시 같은 이력을 반복해서 통째로 못 넣는다)이고, 실패한
 *   회사만 골라 재처리하려면 "이 rcept_no는 elestock 처리를 아직 못 했다"는 별도 상태를
 *   `dart_disclosure`나 별도 테이블에 남겨야 한다 — 이번 태스크 범위 밖이다(계획서에 해당
 *   컬럼·재처리 큐가 없다). `@Transactional`로 여러 회사의 `saveAll`을 한 트랜잭션에 묶는
 *   것도 근본 해결이 아니다: 그러면 뒤 회사 하나의 실패(HTTP 오류 등, 이미 `runCatching`으로
 *   흡수하는 대상)가 아니라 **DB 커넥션 자체가 끊기는 경우**에만 유효한데, 그 경우엔 Tier 4
 *   트리거 전체(최대 150개사, 델타 실측치)를 단일 트랜잭션에 묶는 셈이라 커넥션이 오래
 *   열려 있고, 한 회사만 실패해도 이미 성공한 나머지 회사분까지 통째로 롤백된다 — "한 회사가
 *   실패해도 나머지는 진행한다"(요구사항 6)와 정면으로 충돌한다. 그래서 회사 단위 `saveAll`을
 *   그대로 두고(각각 자체 트랜잭션), 결손은 로그(`failures`)로 드러내 운영이 보고 판단하게
 *   한다 — DONE_WITH_CONCERNS로 보고한다.
 */
@Service
class DartInsiderCollectService(
    private val client: ElestockPort,
    private val store: Store,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    /**
     * 저장에 필요한 것만 추린 좁은 포트. [JpaInsiderStore]가 이걸 구현한다.
     */
    interface Store {
        /** 델타 `rcept_no` 중 실제로 존재하는 공시 행. Tier 4 판정·`stock_code` 조회에 쓴다 */
        fun findDisclosures(rceptNos: Collection<String>): List<DartDisclosureEntity>

        /** 델타 `rcept_no` 범위 안에서 이미 저장된 (rcept_no, repror) 조합 — `uq_insider`와 같은 키 */
        fun findExistingKeys(rceptNos: Collection<String>): Set<Pair<String, String>>

        fun saveAll(rows: List<DartInsiderTradeEntity>)
    }

    fun collect(deltaRceptNos: List<String>, now: LocalDateTime): InsiderCollectSummary {
        // 델타가 비면 아예 부르지 않는다 — 공휴일 등으로 Task 8이 새 공시를 못 찾은 날
        if (deltaRceptNos.isEmpty()) return InsiderCollectSummary(0, 0, emptyList())

        // Tier 4 공시의 회사만 트리거로 삼는다
        val triggers = store.findDisclosures(deltaRceptNos)
            .filter { it.materialTier == DartWhitelist.TIER_INSIDER }
        if (triggers.isEmpty()) return InsiderCollectSummary(0, 0, emptyList())

        val deltaSet = deltaRceptNos.toSet()
        // stock_code는 elestock 응답에 없다 — 트리거 공시에서 가져와 물려준다
        val stockCodeByRcept = triggers.associate { it.rceptNo to it.stockCode }
        val existing = store.findExistingKeys(deltaSet).toMutableSet()

        var calls = 0
        var inserted = 0
        val failures = mutableListOf<String>()

        // 같은 회사에 Tier 4 공시가 둘 이상이어도 elestock은 한 번만 부른다 — 회사 전체 이력이 온다
        triggers.map { it.corpCode }.distinct().forEach { corpCode ->
            calls++
            runCatching { client.fetch(corpCode) }
                .onFailure { e ->
                    // 한 회사가 실패해도 나머지는 진행한다. TX1(공시 수집)은 이미 커밋됐으므로
                    // 여기서 예외를 전파하지 않는다 — 근거는 클래스 KDoc "부분 커밋 판단" 절
                    failures += "corp_code=$corpCode: ${e.message}"
                    log.warn("[DART] elestock 실패 corp_code={}: {}", corpCode, e.message)
                }
                .onSuccess { rows ->
                    // ★핵심★ elestock은 회사 전체 이력(최대 3,395행)을 통째로 준다 — 델타에
                    // 있는 rcept_no만 남긴다. 이 필터가 없으면 재호출마다 같은 이력이 다시 들어온다
                    val fresh = rows
                        .filter { it.rceptNo in deltaSet }
                        // uq_insider (rcept_no, repror) 회피 — 이미 저장된 조합은 다시 넣지 않는다
                        .filter { (it.rceptNo to it.repror) !in existing }
                    fresh.forEach { existing += it.rceptNo to it.repror }

                    val entities = fresh.map { r ->
                        DartInsiderTradeEntity(
                            rceptNo = r.rceptNo,
                            corpCode = r.corpCode,
                            stockCode = stockCodeByRcept[r.rceptNo],
                            repror = r.repror,
                            officerPosition = r.officerPosition,
                            isRegistered = r.isRegistered,
                            majorHolderType = r.majorHolderType,
                            reportDate = r.reportDate,
                            ownedQty = r.ownedQty,
                            changeQty = r.changeQty,
                            ownedRate = r.ownedRate,
                            changeRate = r.changeRate,
                            collectedAt = now,
                        )
                    }
                    if (entities.isNotEmpty()) store.saveAll(entities)
                    inserted += entities.size
                }
        }

        log.info("[DART] 소유변동 적재 calls={} inserted={} failures={}", calls, inserted, failures.size)
        return InsiderCollectSummary(calls, inserted, failures)
    }
}

/** 운영 배선. [DartDisclosureJpaRepository]·[DartInsiderTradeJpaRepository]를 [DartInsiderCollectService.Store]로 감싼다 */
@Component
class JpaInsiderStore(
    private val disclosures: DartDisclosureJpaRepository,
    private val trades: DartInsiderTradeJpaRepository,
) : DartInsiderCollectService.Store {

    override fun findDisclosures(rceptNos: Collection<String>) =
        disclosures.findByRceptNoIn(rceptNos)

    override fun findExistingKeys(rceptNos: Collection<String>): Set<Pair<String, String>> =
        trades.findByRceptNoIn(rceptNos).map { it.rceptNo to it.repror }.toSet()

    override fun saveAll(rows: List<DartInsiderTradeEntity>) {
        trades.saveAll(rows)
    }
}
