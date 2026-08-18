package com.allfolio.dart.query

import com.allfolio.unifiedasset.infrastructure.entity.DartDisclosureEntity
import com.allfolio.unifiedasset.infrastructure.entity.DartInsiderTradeEntity
import com.allfolio.unifiedasset.infrastructure.jpa.DartDisclosureJpaRepository
import com.allfolio.unifiedasset.infrastructure.jpa.DartInsiderTradeJpaRepository
import jakarta.persistence.EntityManager
import org.springframework.stereotype.Component
import org.springframework.stereotype.Service
import java.math.BigDecimal
import java.time.LocalDate
import java.util.UUID

private const val SOURCE_URL_PREFIX = "https://dart.fss.or.kr/dsaf001/main.do?rcpNo="

data class DisclosureItem(
    val rceptNo: String,
    val corpName: String,
    val stockCode: String?,
    val reportNm: String,
    val rceptDt: LocalDate,
    val materialTier: Short?,
    val isCorrection: Boolean,
    val sourceUrl: String,
    /** 같은 (회사, 정규화된 보고서명)으로 접힌 이전 건 수 — 정정공시 묶음. 원본이면 0 */
    val supersededCount: Int,
)

/**
 * 임원·주요주주 소유변동(elestock) 한 건.
 *
 * **★매수·매도를 말하지 않는다.★** 이 DTO에 `changeType`(매수/매도 등) 같은 필드를 추가하지
 * 말 것 — OpenDART `elestock` 응답 자체에 변동사유 필드가 없어 채울 소스가 없다
 * ([DartInsiderTradeEntity] KDoc과 같은 이유). 무상증자·스톡옵션 행사로 인한 소유수량 증가를
 * "매수"로 오표기하면 되돌릴 수 없는 신뢰 손상이다 — 설계 원칙 3·9절(금융 서비스 규제 항목)
 * 이 이 제약의 근거다. 화면 카피에서도 "매수"·"매도"·"매수 신호"를 쓰지 않는다.
 */
data class InsiderTradeItem(
    val rceptNo: String,
    val stockCode: String?,
    val repror: String,
    val officerPosition: String?,
    val isRegistered: Boolean?,
    val majorHolderType: String?,
    val reportDate: LocalDate,
    val ownedQty: Long?,
    val changeQty: Long?,
    val ownedRate: BigDecimal?,
    val changeRate: BigDecimal?,
    val sourceUrl: String,
)

data class DisclosureFeed(
    val items: List<DisclosureItem>,
    val insiderTrades: List<InsiderTradeItem>,
)

/**
 * 사용자 보유종목과 공시를 조회 시점에 조인해 피드를 만든다 (D1 Task 14, 최종 백엔드 태스크).
 *
 * **사용자별 피드 테이블을 사전 계산해 두지 않는다.** 종목 매매마다 재계산해야 하고 사용자
 * 수만큼 행이 불어난다 — 조회 시점에 [Store.findHeldStockCodes]로 보유종목을 얻어 그때그때
 * 조인한다.
 *
 * **보유종목이 없으면 조회를 아예 하지 않는다.** `findMaterial`·`findInsiderTrades`를 빈
 * 컬렉션으로 부르면 JPA가 `IN ()`을 만들거나 조건 없는 쿼리로 새 나갈 수 있다 — 그 전에
 * 여기서 끊는다.
 *
 * **정렬은 Tier 오름차순 → 접수일 내림차순이다.** Tier가 낮을수록(1이 가장 중요) 먼저 나오고,
 * Tier가 없는 행(`null`)은 `Short.MAX_VALUE`로 취급해 맨 뒤로 보낸다. 실측 상장사 6영업일
 * 5,394건 중 2,846건이 Tier 5(정기보고서)였다 — Tier를 무시하고 접수일로만 정렬하면 피드가
 * 반기·사업보고서로 덮여 Tier 1(유상증자·최대주주변경 등 주가 직결 공시)이 아래로 밀린다.
 *
 * **정정공시를 (회사, 정규화된 보고서명)으로 묶어 최신 건만 낸다.** `report_nm_norm`은
 * [com.allfolio.dart.DartReportName.normalize]가 저장 시점에 접두어(`[기재정정]` 등)를 뗀
 * 값이라 원본과 정정본이 같은 그룹으로 모인다. 실측 6영업일치 875건이 `[기재정정]`이라 이
 * 묶기가 실제로 접는 물량이 있다. 접힌 개수는 `supersededCount`로 낸다 — 화면이 "N건 정정
 * 이력" 같은 배지를 달 수 있게.
 */
@Service
class DisclosureFeedService(private val store: Store) {

    interface Store {
        fun findHeldStockCodes(userId: UUID): List<String>
        fun findMaterial(stockCodes: Collection<String>, from: LocalDate): List<DartDisclosureEntity>
        fun findInsiderTrades(stockCodes: Collection<String>, from: LocalDate): List<DartInsiderTradeEntity>
    }

    fun feedFor(userId: UUID, from: LocalDate): DisclosureFeed {
        val held = store.findHeldStockCodes(userId)
        if (held.isEmpty()) return DisclosureFeed(emptyList(), emptyList())

        val items = store.findMaterial(held, from)
            // 정정공시 묶기 — 정규화가 접두어를 떼므로 원본과 정정본이 같은 그룹에 들어간다
            .groupBy { it.corpCode to it.reportNmNorm }
            .map { (_, group) ->
                val latest = group.maxWith(compareBy({ it.rceptDt }, { it.rceptNo }))
                DisclosureItem(
                    rceptNo = latest.rceptNo,
                    corpName = latest.corpName,
                    stockCode = latest.stockCode,
                    reportNm = latest.reportNm,
                    rceptDt = latest.rceptDt,
                    materialTier = latest.materialTier,
                    isCorrection = latest.isCorrection,
                    sourceUrl = SOURCE_URL_PREFIX + latest.rceptNo,
                    supersededCount = group.size - 1,
                )
            }
            .sortedWith(
                compareBy<DisclosureItem> { it.materialTier ?: Short.MAX_VALUE }
                    .thenByDescending { it.rceptDt }
                    .thenByDescending { it.rceptNo },
            )

        val insiders = store.findInsiderTrades(held, from)
            .sortedWith(compareByDescending<DartInsiderTradeEntity> { it.reportDate }.thenByDescending { it.rceptNo })
            .map {
                InsiderTradeItem(
                    rceptNo = it.rceptNo, stockCode = it.stockCode, repror = it.repror,
                    officerPosition = it.officerPosition, isRegistered = it.isRegistered,
                    majorHolderType = it.majorHolderType, reportDate = it.reportDate,
                    ownedQty = it.ownedQty, changeQty = it.changeQty,
                    ownedRate = it.ownedRate, changeRate = it.changeRate,
                    sourceUrl = SOURCE_URL_PREFIX + it.rceptNo,
                )
            }

        return DisclosureFeed(items, insiders)
    }
}

/**
 * 보유종목은 `ua_assets.symbol`이다 — KIS `pdno`(6자리 단축코드)로 채워지고, 공공데이터포털
 * `srtnCd`와 같은 값이라 `dart_disclosure.stock_code`와 그대로 맞는다.
 *
 * **`position_daily`가 아니다.** 그쪽은 `assetId` 기준 일별 스냅샷이라 종목코드 컬럼이 없다.
 *
 * **`type = 'STOCK'` 필터는 장식이 아니다.** 로컬 DB 실측(2026-08-18): `STOCK` 6건은 `symbol`이
 * 전부 6자리인데 `REAL_ESTATE` 1건이 29자리 문자열을 `symbol`에 넣고 있었다. `symbol IS NOT
 * NULL`만으로 거른다는 전제가 이미 틀렸다 — 부동산·해외주식 티커 등 다른 자산 유형도 같은
 * 컬럼을 쓴다. 6자리 종목코드와 우연히 매치될 일은 거의 없지만, 필터 없이 그 행을 조인 후보로
 * 흘리는 것 자체가 설계 결함이라 명시적으로 막는다.
 *
 * **JPA 파생 쿼리가 아니라 네이티브 쿼리인 이유**: `ua_assets`는 이 모듈의 `AssetJpaRepository`
 * 소관이고, 여기서 필요한 건 "보유종목 코드 목록"뿐이라 전체 `AssetEntity`를 끌어올 이유가
 * 없다. 좁은 프로젝션(`symbol`만)을 얻으려 JPQL 대신 네이티브를 쓴 것은 `quantity > 0`
 * 조건과 `DISTINCT`를 한 쿼리에 명시적으로 담기 위해서다.
 */
@Component
class JpaFeedStore(
    private val em: EntityManager,
    private val disclosures: DartDisclosureJpaRepository,
    private val insiders: DartInsiderTradeJpaRepository,
) : DisclosureFeedService.Store {

    @Suppress("UNCHECKED_CAST")
    override fun findHeldStockCodes(userId: UUID): List<String> =
        em.createNativeQuery(
            """
            SELECT DISTINCT symbol FROM ua_assets
            WHERE user_id = :userId AND type = 'STOCK'
              AND symbol IS NOT NULL AND symbol <> '' AND quantity > 0
            """.trimIndent(),
        ).setParameter("userId", userId).resultList as List<String>

    override fun findMaterial(stockCodes: Collection<String>, from: LocalDate) =
        disclosures.findByStockCodeInAndRceptDtGreaterThanEqualAndIsMaterialTrue(stockCodes, from)

    override fun findInsiderTrades(stockCodes: Collection<String>, from: LocalDate) =
        insiders.findByStockCodeInAndReportDateGreaterThanEqualOrderByReportDateDesc(stockCodes, from)
}
