package com.allfolio.market.query

import java.math.BigDecimal
import java.time.LocalDate

/**
 * 시장 화면 한 번의 응답 (AF-104).
 *
 * **네 탭 데이터를 한 번에 싣는다.** 지수 14 + 환율 58 + 금리 6 = 78행이라 합쳐도 작고,
 * 탭마다 따로 부르면 전환마다 스피너가 돈다.
 *
 * **사용자별 데이터가 없다.** "내 통화" 카드는 프런트가 이미 받아 둔 계좌 데이터와 합쳐 만든다.
 * 여기 섞으면 시장 데이터가 포트폴리오에 묶여 캐시도 못 하고 테스트도 무거워진다.
 *
 * [domestic]·[overseas]가 **null이면 플래그로 꺼진 것**(바이트가 서버를 떠난 적이 없다)이고,
 * 빈 리스트면 켜져 있으나 데이터가 없는 것이다.
 *
 * **소비자는 null과 `[]`를 직접 비교하지 말 것.** 탭을 띄울지는 [MarketFlags.indicesEnabled]로
 * 갈라야 하고, 탭을 띄운 뒤 리스트는 "비어 있을 수 있는 것"으로 다뤄 "데이터 없음"을 보여준다.
 * 프런트에서 흔히 쓰는 `?? []` 한 줄이면 "플래그 꺼짐"이 조용히 "데이터 없음"으로 바뀌어,
 * 재배포 약관(AF-108) 때문에 감춘 탭이 빈 화면으로 노출된다.
 */
data class MarketSnapshot(
    val domestic: List<IndexQuoteView>?,
    val overseas: List<IndexQuoteView>?,
    val flags: MarketFlags,
)

/**
 * 지수 한 종.
 *
 * 표시명을 싣지 않는다 — 프런트가 코드로 매핑한다. 설정의 `nameContains`는 KIS 응답 검증용
 * 문자열이지 표시명이 아니다(`"다우존스 산업"`처럼 부분 문자열이다).
 *
 * **수집 시각(`collectedAt`)도 싣지 않는다.** "언제 기준이냐"는 [tradeDate]·[slot]·[marketStatus]가
 * 더 정확히 답한다. 게다가 그 컬럼은 오프셋 없는 UTC `LocalDateTime`이라, 브라우저가
 * `new Date(...)`로 읽으면 로컬 시각으로 오해해 KST 사용자에게 9시간 이른 시각을 보여준다.
 */
data class IndexQuoteView(
    val code: String,
    val price: BigDecimal,
    val change: BigDecimal,
    val changeRate: BigDecimal,
    /** 장중 | 장마감 | 개장전 */
    val marketStatus: String,
    val tradeDate: LocalDate,
    /** OPEN | MID | CLOSE. 화면이 "언제 기준인지"를 말할 때 쓴다 */
    val slot: String,
)

data class MarketFlags(
    /** AF-108 재배포 미결. false면 지수를 아예 싣지 않는다 */
    val indicesEnabled: Boolean,
)
