package com.allfolio.unifiedasset.infrastructure.entity

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.time.LocalDate
import java.time.LocalDateTime

/**
 * 배치 실행 기록 한 건 (D1).
 *
 * **`status`가 문자열(`SUCCESS`/`PARTIAL`/`FAILED`)이고 enum이 아닌 이유**: 계획이 문자열로
 * 정했고 마이그레이션도 `VARCHAR(20)`이다. OpenDART `status="013"`(공휴일 등 조회 데이터 없음)은
 * 실패가 아니라 `SUCCESS`/`new_count=0`으로 기록한다 — 그러지 않으면 공휴일마다 배치가
 * 빨갛게 된다.
 *
 * 카운터(`pagesFetched`·`apiCalls`·`newCount`·`elestockCalls`)가 `var`인 이유는 실행 도중
 * 누적 갱신하기 때문이다 — 실행 시작 시 행을 만들고 진행에 따라 값을 올린 뒤 종료 시
 * `status`·`finishedAt`을 채운다.
 *
 * **`elestockCalls`는 현재 배선이 없어 항상 `0`이다 — 의도적으로 죽여 둔 것이다.**
 * [com.allfolio.dart.list.DartDisclosureCollectService.collect]가 이 run 행을 저장하고
 * **끝난 뒤에** [com.allfolio.dart.insider.DartInsiderCollectService.collect]가 도는 순서라,
 * 소유변동 단계의 `calls`(`InsiderCollectSummary.calls`)를 알 시점엔 이미 이 행이 커밋돼 있다.
 * 채우려면 (a) 소유변동 단계가 끝난 뒤 run 행을 다시 읽어 갱신·재저장하거나 (b) 저장 시점을
 * 뒤로 미뤄야 하는데, 둘 다 "`collect()`가 분기(성공/실패)당 정확히 한 번만 감사 행을 저장한다"는
 * [com.allfolio.dart.list.DartDisclosureCollectService] KDoc의 원칙(TX1 커밋과 감사 로그
 * 저장 실패를 분리하는 근거이기도 하다)과 충돌한다. OpenDART 일일 한도(20,000) 대비 elestock
 * 호출은 델타 실측 최대 150개사 수준이라 관측 가치가 낮은 반면, 1회 기록 원칙을 깨는 대가
 * (감사 행 이중 저장·부분 커밋 재조사 복잡도 증가)가 더 크다고 판단해 지금은 손대지 않는다.
 * 필요해지면 (a)를 먼저 검토하되 1회 기록 원칙과의 충돌부터 정리할 것.
 */
@Entity
@Table(name = "dart_collection_run")
class DartCollectionRunEntity(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long? = null,

    @Column(name = "run_at", nullable = false)
    val runAt: LocalDateTime,

    @Column(name = "bgn_de", nullable = false)
    val bgnDe: LocalDate,

    @Column(name = "end_de", nullable = false)
    val endDe: LocalDate,

    @Column(name = "pages_fetched", nullable = false)
    var pagesFetched: Int = 0,

    @Column(name = "api_calls", nullable = false)
    var apiCalls: Int = 0,

    @Column(name = "new_count", nullable = false)
    var newCount: Int = 0,

    /** 항상 0 — 배선 없음. 근거는 클래스 KDoc "`elestockCalls`는 현재 배선이 없어..." 절 */
    @Column(name = "elestock_calls", nullable = false)
    var elestockCalls: Int = 0,

    @Column(name = "status", nullable = false, length = 20)
    var status: String,

    @Column(name = "error_msg", columnDefinition = "text")
    var errorMsg: String?,

    @Column(name = "finished_at")
    var finishedAt: LocalDateTime?,
)
