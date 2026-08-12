package com.allfolio.unifiedasset.infrastructure.jpa

import com.allfolio.unifiedasset.infrastructure.entity.HanaFxQuoteEntity
import org.springframework.data.jpa.repository.JpaRepository
import java.time.LocalDate
import java.util.UUID

interface HanaFxQuoteJpaRepository : JpaRepository<HanaFxQuoteEntity, UUID> {

    /**
     * 그 통화의 가장 최근 고시 한 건. 평가 경로가 쓴다.
     * 같은 날 여러 회차가 쌓이므로 기준일 다음에 회차까지 내림차순이어야 한다.
     */
    fun findTopByCurrencyOrderByBaseDateDescRoundNoDesc(currency: String): HanaFxQuoteEntity?

    /** 수집 시 같은 회차의 기존 행을 한 번에 읽어 덮어쓸 대상을 가려낸다 */
    fun findAllByBaseDateAndRoundNo(baseDate: LocalDate, roundNo: Int): List<HanaFxQuoteEntity>
}
