package com.allfolio.market.fsc

/**
 * 공공데이터포털 호출 실패. `FredApiException`과 같은 모양이다 — 코드로 갈리고 본문을 싣지 않는다.
 *
 * **오퍼레이션이 아니라 포털 공용이다.** 원자재 클라이언트(`market.commodity.fsc.FscCommodityClient`)
 * 안에서 태어났지만 원자재 도메인 개념이 아니다 — 코드 어휘(`NO_KEY` · `EMPTY` · `HTTP-nnn` ·
 * `MALFORMED` · `IO` · `RESULT-nn` · `TRUNCATED`)가 인증·응답 봉투에서 나오는 것이라
 * 같은 포털의 어느 오퍼레이션을 부르든 글자 하나 안 바뀐다. 그래서 소스별 패키지가 아니라
 * `market.fsc`에 둔다.
 *
 * **오퍼레이션마다 예외 타입을 새로 만들지 말 것.** 클론을 만들면 그 순간부터 수집 서비스가
 * 타입을 그만큼 다 알아야 하고, 하나를 빠뜨리면 그 소스의 실패만 격리를 빠져나가 배치 전체를
 * 죽인다 — 그런데 코드도 메시지도 똑같아서 로그만 봐선 왜 한쪽만 다른지 알 수 없다.
 *
 * **`detail`에 응답 본문·`resultMsg`·요청 URL을 싣지 말 것.** 인증키가 쿼리 파라미터에 실리고,
 * 포털의 기본 오류 페이지는 요청 URI를 되울려 렌더링한다. 이 메시지는 수집 요약을 타고
 * 어드민 응답과 GitHub Actions 주석까지 나가는 값이다 — 방어의 근거는 각 클라이언트의 KDoc에 있다.
 */
class FscApiException(val code: String, val detail: String) :
    RuntimeException("FSC 오류 [$code] $detail")
