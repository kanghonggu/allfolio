package com.allfolio.market.index

/**
 * 스케줄 지점 (AF-101).
 *
 * KIS 지수 응답에 기준시각이 없어 조회 시각을 키로 쓸 수 없다.
 * 대신 "그날의 어느 지점인가"를 키에 넣어, cron이 밀려도 한 건으로 수렴시킨다.
 */
enum class IndexSlot { OPEN, MID, CLOSE }

/**
 * 해외 지수 수집 슬롯 (AF-110). `market-index.overseas[].schedule` 값과 이름이 같아야 한다.
 *
 * **국내의 [IndexSlot]과 뜻이 다르다.** 저 셋은 "하루 중 어느 지점"이지만 이 둘은 "어느 시장군"이다 —
 * 해외는 일봉이라 저장 슬롯이 `CLOSE` 하나로 고정이고, 이 enum은 **어느 cron이 어느 지수 묶음을
 * 부를지**만 가른다(US = 미국·유럽 6종 / ASIA = 일본·홍콩·중국 3종).
 * 유로스톡스가 US에 실리는 이유는 `application.yml`의 주석에 있다.
 *
 * **문자열이 아니라 enum으로 받는 이유.** 컨트롤러가 이걸 `String`으로 받으면 URL 오타
 * (`schedule=Us`)가 설정과 대조에서 0건으로 떨어져 `requested == 0` → **500**으로 나온다.
 * 그런데 500은 "설정에 지수가 없다"는 뜻이라 운영자를 `application.yml`로 보내는데, 진짜 원인은
 * 워크플로가 실어 보낸 URL이다 — 멀쩡한 yml을 한참 들여다본 뒤에야 딴 데를 보게 된다.
 * enum이면 Spring이 변환 단계에서 400을 내고 문구에 받은 값이 실린다. 국내 [IndexSlot]이
 * 정확히 그 일을 하고 있다. **`String`으로 "단순화"하지 말 것.**
 */
enum class OverseasSchedule { US, ASIA }

/** 시장 상태. KIS 응답에 없어 우리가 판정한다. */
enum class MarketStatus(val label: String) {
    PRE_OPEN("개장전"),
    OPEN("장중"),
    CLOSED("장마감"),
}
