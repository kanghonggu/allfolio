package com.allfolio.dart.query

import com.allfolio.unifiedasset.infrastructure.entity.DartDisclosureEntity
import com.allfolio.unifiedasset.infrastructure.entity.DartInsiderTradeEntity
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.LocalDate
import java.time.LocalDateTime
import java.util.UUID

/**
 * [DisclosureFeedService]는 [DisclosureFeedService.Store]를 인메모리로 세워 단위로 검증한다.
 * `type='STOCK'` 필터·부분 인덱스 술어 같은 조인 세부는 여기서 안 잡힌다 — 그건
 * `JpaFeedStoreTest`(H2, 실제 네이티브 쿼리)의 몫이다. 이 테스트는 서비스의 순수 로직
 * (정렬·정정공시 묶기·원문 링크·소유변동 분리)만 본다.
 */
class DisclosureFeedServiceTest {

    private val userId = UUID.randomUUID()
    private val from = LocalDate.of(2026, 8, 1)
    private val now = LocalDateTime.of(2026, 8, 18, 19, 0)

    private fun disclosure(
        rceptNo: String, stockCode: String?, tier: Short?, rceptDt: LocalDate,
        reportNm: String = "유상증자결정", corpCode: String = "C1",
    ) = DartDisclosureEntity(
        rceptNo = rceptNo, corpCode = corpCode, corpName = "회사", stockCode = stockCode,
        corpCls = "Y", reportNm = reportNm, reportNmNorm = reportNm,
        rceptDt = rceptDt, flrNm = "회사", rm = null,
        isMaterial = tier != null, materialTier = tier, isCorrection = false, collectedAt = now,
    )

    private class FakeStore(
        val holdings: List<String>,
        val disclosures: List<DartDisclosureEntity>,
        val insiders: List<DartInsiderTradeEntity> = emptyList(),
    ) : DisclosureFeedService.Store {
        override fun findHeldStockCodes(userId: UUID) = holdings

        // stockCodes가 비었는데 호출되면 즉시 터뜨린다. 빈 컬렉션을 그냥 필터링해서 빈 결과를
        // 주는 가짜라면 "보유종목이 없으면 조회 자체를 안 한다"는 규칙이 있으나 없으나 이
        // 테스트는 계속 초록이다 — 실제 JPA에서 `stock_code IN ()`이 문제가 될 수 있는 것과
        // 별개로, 서비스가 그 경로를 안 타는지를 여기서 직접 못 박는다.
        override fun findMaterial(stockCodes: Collection<String>, from: LocalDate): List<DartDisclosureEntity> {
            require(stockCodes.isNotEmpty()) { "보유종목이 없을 때는 findMaterial을 호출하면 안 된다" }
            return disclosures.filter { it.stockCode in stockCodes && it.isMaterial && it.rceptDt >= from }
        }

        override fun findInsiderTrades(stockCodes: Collection<String>, from: LocalDate): List<DartInsiderTradeEntity> {
            require(stockCodes.isNotEmpty()) { "보유종목이 없을 때는 findInsiderTrades를 호출하면 안 된다" }
            return insiders.filter { it.stockCode in stockCodes && it.reportDate >= from }
        }
    }

    @Test
    fun `보유종목의 공시만 나온다`() {
        val store = FakeStore(
            holdings = listOf("005930"),
            disclosures = listOf(
                disclosure("R1", "005930", 1, LocalDate.of(2026, 8, 18)),
                disclosure("R2", "000660", 1, LocalDate.of(2026, 8, 18)),
            ),
        )

        val feed = DisclosureFeedService(store).feedFor(userId, from)

        assertThat(feed.items.map { it.rceptNo }).containsExactly("R1")
    }

    @Test
    fun `Tier 오름차순 다음 접수일 내림차순으로 정렬한다`() {
        // 세 건을 서로 다른 회사(corpCode)로 둔다 — 같은 회사·같은 보고서명이면 정정공시
        // 묶기(다음 테스트가 검증)가 먼저 접어 버려 이 정렬 테스트가 그룹핑과 뒤섞인다.
        val store = FakeStore(
            holdings = listOf("005930"),
            disclosures = listOf(
                disclosure("R_T5_NEW", "005930", 5, LocalDate.of(2026, 8, 18), "반기보고서", corpCode = "C1"),
                disclosure("R_T1_OLD", "005930", 1, LocalDate.of(2026, 8, 11), corpCode = "C2"),
                disclosure("R_T1_NEW", "005930", 1, LocalDate.of(2026, 8, 18), corpCode = "C3"),
            ),
        )

        val feed = DisclosureFeedService(store).feedFor(userId, from)

        assertThat(feed.items.map { it.rceptNo })
            .containsExactly("R_T1_NEW", "R_T1_OLD", "R_T5_NEW")
    }

    @Test
    fun `보유종목이 없으면 조회하지 않고 빈 피드다`() {
        val store = FakeStore(holdings = emptyList(), disclosures = listOf(
            disclosure("R1", "005930", 1, LocalDate.of(2026, 8, 18)),
        ))

        assertThat(DisclosureFeedService(store).feedFor(userId, from).items).isEmpty()
    }

    @Test
    fun `같은 회사 같은 보고서는 최신 건만 낸다`() {
        // [기재정정]은 새 rcept_no를 받는다. 정규화가 접두어를 떼므로 같은 그룹에 들어간다.
        val store = FakeStore(
            holdings = listOf("005930"),
            disclosures = listOf(
                disclosure("R_ORIG", "005930", 5, LocalDate.of(2026, 8, 11), "반기보고서 (2026.06)"),
                disclosure("R_FIX", "005930", 5, LocalDate.of(2026, 8, 18), "반기보고서 (2026.06)"),
            ),
        )

        val feed = DisclosureFeedService(store).feedFor(userId, from)

        assertThat(feed.items.map { it.rceptNo }).containsExactly("R_FIX")
        assertThat(feed.items.single().supersededCount).isEqualTo(1)
    }

    @Test
    fun `원문 링크를 만든다`() {
        val store = FakeStore(listOf("005930"), listOf(
            disclosure("20260818800172", "005930", 1, LocalDate.of(2026, 8, 18)),
        ))

        assertThat(DisclosureFeedService(store).feedFor(userId, from).items.single().sourceUrl)
            .isEqualTo("https://dart.fss.or.kr/dsaf001/main.do?rcpNo=20260818800172")
    }

    @Test
    fun `소유변동을 별도 섹션으로 낸다`() {
        val store = FakeStore(
            holdings = listOf("005930"),
            disclosures = emptyList(),
            insiders = listOf(
                DartInsiderTradeEntity(
                    id = 1L, rceptNo = "R1", corpCode = "C1", stockCode = "005930",
                    repror = "홍길동", officerPosition = "상무", isRegistered = false,
                    majorHolderType = null, reportDate = LocalDate.of(2026, 8, 18),
                    ownedQty = 1000L, changeQty = -50L,
                    ownedRate = java.math.BigDecimal("0.01"),
                    changeRate = java.math.BigDecimal("0.00"), collectedAt = now,
                ),
            ),
        )

        val feed = DisclosureFeedService(store).feedFor(userId, from)

        assertThat(feed.insiderTrades).hasSize(1)
        with(feed.insiderTrades.single()) {
            assertThat(changeQty).isEqualTo(-50L)
            assertThat(sourceUrl).isEqualTo("https://dart.fss.or.kr/dsaf001/main.do?rcpNo=R1")
        }
    }
}
