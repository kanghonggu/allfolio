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
         * 시장 현지 마감 시각(`"16:00"` 형식, [zoneId] 기준). 최신 봉이 오늘 것이어도
         * 이 시각을 지났으면 확정된 종가로 본다.
         *
         * **없으면 예약 수집 전건이 `장중`으로 저장된다.** 아시아 슬롯은 08:30 UTC = 홍콩 16:30,
         * 미국 슬롯은 21:30 UTC = 뉴욕 17:30이라 **둘 다 마감 이후**인데, 봉 날짜만 보면
         * "현지 오늘"이라 장중이 된다. 그러면 `장마감`은 주말·휴장에만 나와 라벨의 뜻이 뒤집힌다.
         *
         * 시각만 두고 오프셋을 두지 않는 이유: [zoneId]와 함께 쓰면 서머타임이 자동으로 맞는다.
         * 유로스톡스 마감 17:30은 UTC로 여름 15:30·겨울 16:30을 오가지만 현지 시각은 그대로다.
         *
         * **근사치다.** 반일장(추수감사절 다음날 미국 13:00, 크리스마스 이브 홍콩 12:00),
         * 특별 휴장, 거래소 개편(도쿄는 2024-11에 15:00 → 15:30으로 늘렸다)까지 따라가지 않는다.
         * **정확도가 실제로 문제되는 경우는 장중 수동 실행뿐이다** — 예약 실행은 전부 마감 한참
         * 뒤이고 가장 빠듯한 홍콩도 마감 30분 뒤라 몇 분 오차로 판정이 뒤집히지 않는다.
         * 그러니 분 단위를 다듬는 데 시간 쓰지 말 것.
         */
        var closeLocalTime: String = ""
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
