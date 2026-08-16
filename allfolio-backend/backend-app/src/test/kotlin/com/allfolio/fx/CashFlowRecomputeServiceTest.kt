package com.allfolio.fx

import com.allfolio.unifiedasset.application.port.FxConverter
import com.allfolio.unifiedasset.application.port.KrwConversion
import com.allfolio.unifiedasset.domain.cashflow.CashFlow
import com.allfolio.unifiedasset.domain.cashflow.FlowType
import com.allfolio.unifiedasset.infrastructure.entity.CashFlowEntity
import com.allfolio.unifiedasset.infrastructure.jpa.CashFlowJpaRepository
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.mockito.Mockito.mock
import java.math.BigDecimal
import java.time.LocalDate
import java.time.LocalDateTime
import java.util.UUID

/**
 * 현금흐름 KRW 환산액 소급 재계산 (AF-100 2단계).
 *
 * `FxConverter`는 인터페이스라 익명 객체로 충분하다 — Mockito를 쓰면 `toKrwOn`의
 * non-null 파라미터에서 `any()`가 스터빙 시점에 NPE를 낸다(이 저장소에서 이미 두 번 물린 함정).
 *
 * `CashFlowJpaRepository`는 `JpaRepository` 상속이라 메서드가 수십 개다 —
 * 위임 + 부분 오버라이드로 실제로 쓰는 둘만 구현한다.
 */
class CashFlowRecomputeServiceTest {

    private val userId = UUID.randomUUID()

    // 드라이런이 기본값인 게 이 기능의 안전장치 전부다. 저장이 한 번이라도 일어나면
    // 사용자가 보고서를 보기 전에 금융 이력이 바뀐다.
    @Test
    fun `드라이런은 저장하지 않는다`() {
        val repo = FakeRepo(listOf(flow(amountKrw = "140000")))
        val service = CashFlowRecomputeService(repo, converter("1300"))

        val summary = service.recompute(apply = false)

        assertThat(repo.stored).isEmpty()
        assertThat(summary.changed).isEqualTo(1)
    }

    @Test
    fun `apply면 그 날짜 환율로 저장한다`() {
        val repo = FakeRepo(listOf(flow(amount = "100", amountKrw = "140000")))
        val service = CashFlowRecomputeService(repo, converter("1300"))

        service.recompute(apply = true)

        assertThat(repo.stored).hasSize(1)
        assertThat(repo.stored[0].amountKrw).isEqualByComparingTo("130000")
    }

    // 재계산은 순수 함수라 이미 맞는 행은 자기 자신으로 계산된다.
    // 불필요한 UPDATE를 내면 두 번째 실행이 첫 번째와 다른 일을 하는 셈이 된다.
    @Test
    fun `값이 그대로면 저장하지 않는다`() {
        val repo = FakeRepo(listOf(flow(amount = "100", amountKrw = "130000")))
        val service = CashFlowRecomputeService(repo, converter("1300"))

        val summary = service.recompute(apply = true)

        assertThat(repo.stored).isEmpty()
        assertThat(summary.changed).isZero()
        assertThat(summary.unchanged).isEqualTo(1)
    }

    // 과거 환율이 없으면 현재 환율 근사가 그대로 유지된다. 그 행이 몇 건인지가
    // 보고서의 핵심이다 — 이걸 모르면 "다 고쳤다"고 착각한다.
    @Test
    fun `과거 환율이 없는 행은 추정치로 세고 값을 바꾸지 않는다`() {
        val repo = FakeRepo(listOf(flow(amount = "100", amountKrw = "140000")))
        val service = CashFlowRecomputeService(repo, converter(rate = null))

        val summary = service.recompute(apply = true)

        assertThat(summary.stillEstimated).isEqualTo(1)
        assertThat(repo.stored).isEmpty()
    }

    // amount_krw는 부호를 담지 않는다 — signedKrw()가 type에서 파생한다.
    // 쓰기 경로와 같은 toKrwOn을 쓰면 규약이 저절로 보존되므로, 부호를 손대는 코드가 없어야 한다.
    @Test
    fun `출금 행의 부호 규약이 보존된다`() {
        val repo = FakeRepo(listOf(flow(type = FlowType.WITHDRAWAL, amount = "100", amountKrw = "140000")))
        val service = CashFlowRecomputeService(repo, converter("1300"))

        service.recompute(apply = true)

        val saved = repo.stored.single().toDomain()
        assertThat(saved.amountKrw).isEqualByComparingTo("130000")    // 양수 크기
        assertThat(saved.signedKrw()).isEqualByComparingTo("-130000") // 부호는 type에서
    }

    // 같은 id·createdAt을 유지해야 JPA가 INSERT가 아니라 UPDATE로 처리한다.
    // 새 id를 만들면 원본이 남은 채 중복 행이 생겨 입출금이 두 배가 된다.
    @Test
    fun `id와 생성시각을 보존한다`() {
        val original = flow(amountKrw = "140000")
        val repo = FakeRepo(listOf(original))
        val service = CashFlowRecomputeService(repo, converter("1300"))

        service.recompute(apply = true)

        val saved = repo.stored.single().toDomain()
        assertThat(saved.id).isEqualTo(original.id)
        assertThat(saved.createdAt).isEqualTo(original.createdAt)
    }

    @Test
    fun `보고서에 변동 폭 상위가 담긴다`() {
        val repo = FakeRepo(
            listOf(
                flow(amount = "100", amountKrw = "140000"),   // −10,000
                flow(amount = "1000", amountKrw = "1400000"), // −100,000
            ),
        )
        val service = CashFlowRecomputeService(repo, converter("1300"))

        val summary = service.recompute(apply = false)

        assertThat(summary.topChanges.first().delta.abs()).isEqualByComparingTo("100000")
        assertThat(summary.totalDelta).isEqualByComparingTo("-110000")
    }

    // 통화별 집계는 "어느 통화가 얼마나 틀려 있었나"를 답하는 보고서의 유일한 항목이다.
    // **변이 테스트에서 이 집계를 통째로 지웠을 때 아무 테스트도 안 잡았다** — 그래서 넣는다.
    // scanned는 값이 안 바뀐 행까지 세고, changed·totalDelta는 바뀐 행만 센다.
    @Test
    fun `통화별로 대상 수와 변동액을 따로 집계한다`() {
        val repo = FakeRepo(
            listOf(
                flow(currency = "USD", amount = "100", amountKrw = "140000"), // −10,000
                flow(currency = "USD", amount = "200", amountKrw = "260000"), // 무변화
                flow(currency = "BTC", amount = "10", amountKrw = "20000"),   // −7,000
            ),
        )
        val service = CashFlowRecomputeService(repo, converter("1300"))

        val byCurrency = service.recompute(apply = false).byCurrency

        assertThat(byCurrency.keys).containsExactlyInAnyOrder("USD", "BTC")
        assertThat(byCurrency.getValue("USD").scanned).isEqualTo(2)
        assertThat(byCurrency.getValue("USD").changed).isEqualTo(1)
        assertThat(byCurrency.getValue("USD").totalDelta).isEqualByComparingTo("-10000")
        assertThat(byCurrency.getValue("BTC").scanned).isEqualTo(1)
        assertThat(byCurrency.getValue("BTC").changed).isEqualTo(1)
        assertThat(byCurrency.getValue("BTC").totalDelta).isEqualByComparingTo("-7000")
    }

    // ── helpers ──────────────────────────────────────────────

    private fun flow(
        currency: String = "USD",
        amount: String = "100",
        amountKrw: String = "140000",
        type: FlowType = FlowType.DEPOSIT,
        flowDate: LocalDate = LocalDate.of(2024, 5, 20),
    ) = CashFlow.reconstruct(
        id = UUID.randomUUID(), userId = userId, accountId = null, flowDate = flowDate,
        type = type, amount = BigDecimal(amount), currency = currency,
        amountKrw = BigDecimal(amountKrw), memo = null, createdAt = LocalDateTime.now(),
    )

    /** 요청한 날짜의 환율을 그대로 돌려주는 페이크. rate=null이면 "과거 환율 없음". */
    private fun converter(rate: String?, estimated: Boolean = rate == null) = object : FxConverter {
        override fun toKrw(amount: BigDecimal, currency: String): BigDecimal =
            amount.multiply(BigDecimal("1400"))

        override fun rateOf(currency: String): BigDecimal = BigDecimal("1400")

        override fun toKrwOn(amount: BigDecimal, currency: String, date: LocalDate): KrwConversion =
            KrwConversion(
                amountKrw = amount.multiply(BigDecimal(rate ?: "1400")),
                rateDate = if (rate == null) null else date,
                estimated = estimated,
            )
    }

    /**
     * 위임 대상 목은 **스터빙하지 않는다** — 쓰지 않는 메서드를 채우기 위한 자리일 뿐이다.
     * 실제로 불리는 둘만 오버라이드한다.
     */
    private class FakeRepo(rows: List<CashFlow>) :
        CashFlowJpaRepository by mock(CashFlowJpaRepository::class.java) {

        val stored = mutableListOf<CashFlowEntity>()
        private val all = rows.map { CashFlowEntity.from(it) }

        override fun findNonKrwOrderByFlowDate(): List<CashFlowEntity> = all
        override fun <S : CashFlowEntity> save(entity: S): S = entity.also { stored += it }
    }
}
