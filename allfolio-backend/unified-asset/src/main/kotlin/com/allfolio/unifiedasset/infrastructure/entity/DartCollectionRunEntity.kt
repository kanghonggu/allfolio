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

    @Column(name = "elestock_calls", nullable = false)
    var elestockCalls: Int = 0,

    @Column(name = "status", nullable = false, length = 20)
    var status: String,

    @Column(name = "error_msg", columnDefinition = "text")
    var errorMsg: String?,

    @Column(name = "finished_at")
    var finishedAt: LocalDateTime?,
)
