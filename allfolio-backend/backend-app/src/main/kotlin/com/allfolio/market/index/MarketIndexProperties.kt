package com.allfolio.market.index

import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.stereotype.Component

@Component
@ConfigurationProperties(prefix = "market-index")
class MarketIndexProperties {
    var domestic: List<DomesticIndex> = emptyList()

    var overseas: List<OverseasIndex> = emptyList()

    class DomesticIndex {
        /** 우리가 정한 canonical 코드. DB의 index_code가 된다 */
        var code: String = ""
        /** KIS FID_INPUT_ISCD */
        var kisIscd: String = ""
    }

    /**
     * 해외 지수 한 종 (AF-110).
     *
     * [nameContains]가 이 설정의 핵심이다 — 아래 KDoc 참조.
     */
    class OverseasIndex {
        /** 우리가 정한 canonical 코드. DB의 index_code가 된다 */
        var code: String = ""
        /** KIS FID_INPUT_ISCD. 미국계는 `SPX`·`.DJI`, 아시아·유럽계는 `HK#HS` 형태 */
        var kisIscd: String = ""
        /**
         * 시장 현지 타임존. **진행 중인 봉 판별에만 쓴다** —
         * 최신 봉의 날짜가 이 타임존의 오늘이면 아직 장이 안 끝난 것이다.
         * 수집 시각(아래 [schedule])과는 별개다: 유로스톡스는 유럽 타임존이지만 미국 슬롯에 실린다.
         */
        var zoneId: String = ""
        /** 어느 cron 슬롯에 실을지. US | ASIA */
        var schedule: String = ""
        /**
         * KIS 응답의 `hts_kor_isnm`에 반드시 들어 있어야 하는 문자열.
         *
         * **틀린 코드를 넣었을 때 이것 말고는 잡을 방법이 없다.** 마스터에는 한 글자 차이인 것들이
         * 줄줄이 붙어 있다 — 나스닥100 옆 `XNDXL`(레버리지)·`XNDXS1/S2`(인버스), 항셍 옆
         * `HSCE`(홍콩H)·`HK#HSSI`(소형주), 상해의 `CH#SHA`/`CH#SHB`(A·B주), 다우 옆
         * `.DJT`(운송)·`.DJU`(유틸리티).
         *
         * `IndexGuards`는 값끼리의 정합성만 보므로 **엉뚱한 지수의 응답도 내부적으로 일관돼
         * 그대로 통과한다.** 예외도 경고도 없이 그럴듯한 숫자가 저장되고, 화면엔 "항셍"이라 쓰인 채
         * 홍콩H지수가 뜬다. 그래서 코드가 아니라 **KIS가 돌려준 이름**으로 대조한다.
         */
        var nameContains: String = ""
    }
}
