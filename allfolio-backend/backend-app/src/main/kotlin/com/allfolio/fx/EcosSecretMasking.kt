package com.allfolio.fx

/**
 * ECOS 인증키를 상류가 준 문자열에서 가린다.
 *
 * 인증키가 요청 URL의 **첫 경로 세그먼트**라, 서버가 준 문자열에 그대로 되울려 올 수 있다 —
 * Tomcat 계열 기본 오류 페이지는 요청 URI를 본문에 렌더링하고, ECOS 인증 오류는
 * "등록되지 않은 인증키입니다: XXX" 형태로 키를 경로 밖에 단독으로 싣는다.
 * **그 오류는 HTTP 200으로 온다** — 2xx라고 안전하다고 볼 수 없는 이유다.
 *
 * 두 클라이언트([EcosStatisticSearchClient]·[EcosStatListClient])가 같은 방어를 하므로 여기로 뺀다.
 * 한쪽에만 두면 다른 쪽이 조용히 어긋난다.
 *
 * **빈 키로 replace를 걸면 안 된다.** 빈 문자열은 모든 위치에서 일치해서 글자 사이마다 마스크가
 * 끼어든 쓰레기가 나온다. 키가 설정되지 않았으면 가릴 것도 없다.
 *
 * 여기까지가 공통이다. 이 마스킹은 **정확히 일치**할 때만 들어서 퍼센트 인코딩된 키
 * (`KEY%31%32`)는 못 잡는다. 그래서 본문을 200자로 **자르는** [EcosStatisticSearchClient]는
 * 요청 URI 통째 제거를 덧댄다 — 자르면 경계에 키 조각이 남기 때문이다.
 * 원본을 그대로 돌려주는 [EcosStatListClient]는 그 정규식을 쓰지 않는다:
 * JSON을 보러 온 사람에게서 그 JSON을 뺏는 대가가 이득보다 크다.
 */
internal fun maskEcosApiKey(raw: String, apiKey: String): String =
    if (apiKey.isBlank()) raw else raw.replace(apiKey, "***")
