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

    /**
     * 전체에서 가장 최근 고시 한 건. 여기서 얻은 `(baseDate, roundNo)`로 그 회차 전 통화를 읽는다.
     * 통화마다 최신을 따로 찾지 않는 이유: 58번 왕복이 되고, 통화별로 회차가 갈려
     * 한 화면에 서로 다른 회차가 섞인다.
     */
    fun findTopByOrderByBaseDateDescRoundNoDesc(): HanaFxQuoteEntity?

    /**
     * [baseDate]보다 앞선 기준일 중 가장 최근 고시 한 건.
     *
     * **직전 "회차"가 아니라 직전 "기준일"이다.** 하나은행은 하루에 회차가 여러 번 나오므로
     * 직전 회차와 비교하면 전일대비가 아니라 장중 변동이 된다. 연휴로 며칠이 비어도
     * 이 쿼리가 알아서 그 앞의 영업일을 찾는다.
     */
    fun findTopByBaseDateLessThanOrderByBaseDateDescRoundNoDesc(baseDate: LocalDate): HanaFxQuoteEntity?
}
