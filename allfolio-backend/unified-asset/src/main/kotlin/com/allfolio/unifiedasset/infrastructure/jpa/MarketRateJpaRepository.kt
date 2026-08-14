package com.allfolio.unifiedasset.infrastructure.jpa

import com.allfolio.unifiedasset.infrastructure.entity.MarketRateEntity
import org.springframework.data.jpa.repository.JpaRepository
import java.time.LocalDate
import java.util.UUID

interface MarketRateJpaRepository : JpaRepository<MarketRateEntity, UUID> {

    /**
     * 그 지표의 구간 내 기존 행. 수집은 구간을 통째로 받아 덮으므로 한 번에 읽는다 —
     * 행마다 조회하면 2주 x 6종목이 84번의 왕복이 된다(Neon은 원격이다).
     */
    fun findByRateCodeAndQuoteDateBetween(
        rateCode: String,
        from: LocalDate,
        to: LocalDate,
    ): List<MarketRateEntity>

    /**
     * 여러 지표의 구간 내 행을 **쿼리 한 번으로** 준다. 시장 화면(AF-104) 조회가 쓴다.
     *
     * 위 [findByRateCodeAndQuoteDateBetween]과 따로 두는 이유: 그쪽은 수집 경로라 "지표 하나의
     * 구간을 통째로 받아 덮는다"가 단위이고, 여기는 조회 경로라 "화면 한 장에 필요한 전 지표"가
     * 단위다. 지표마다 부르면 운영 설정 6종에 원격 Neon 왕복이 6번 난다.
     *
     * **`rateCode`가 지표 간 유일하다는 데 조용히 기댄다.** 호출부는 결과를 `rateCode`로 묶어
     * 쓰므로, 설정 두 항목이 같은 코드를 쓰면 두 지표가 한 그룹으로 접혀 하나가 화면에서 사라진다.
     * 그 중복은 `MarketRateProperties.validate()`가 기동 시점에 막아 준다 — 그 검사가 없어지면
     * 여기가 먼저 깨진다.
     *
     * **순서는 보장되지 않는다** — 최신·직전을 고르려면 호출부가 `quoteDate`로 정렬해야 한다.
     * `rateCodes`가 비면 `IN ()`이 되어 벤더에 따라 문법 오류다. 호출부가 먼저 걸러야 한다.
     */
    fun findByRateCodeInAndQuoteDateBetween(
        rateCodes: Collection<String>,
        from: LocalDate,
        to: LocalDate,
    ): List<MarketRateEntity>
}
